package org.salvami.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

enum class Status { PENDING, RUNNING, COMPLETED, FAILED }

data class Tag(val id: String, val name: String, val color: Long)

data class MediaItem(
    val id: String,
    val file: String,          // file name inside Library.mediaDir
    val video: Boolean,
    val sourceUrl: String,
    val platform: String,
    val username: String,
    val title: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val savedAt: Long,
    val favorite: Boolean = false,
    val memo: String = "",
    val tags: List<String> = emptyList(),
    /** Id of the download that brought several items at once (a carousel, a multi-photo post); "" when alone. */
    val group: String = "",
    /** Order inside [group], as in the original post. */
    val position: Int = 0,
)

data class DownloadEntry(
    val id: String,
    val url: String,
    val status: Status,
    val message: String,
    val time: Long,
    val count: Int,
)

data class LibState(
    val items: List<MediaItem> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val history: List<DownloadEntry> = emptyList(),
    val profiles: List<Profile> = emptyList(),
)

/** The whole library lives in memory and is persisted as one JSON file. */
object Library {
    private val _state = MutableStateFlow(LibState())
    val state: StateFlow<LibState> = _state

    private var file: File? = null
    lateinit var mediaDir: File
        private set
    lateinit var avatarDir: File
        private set
    private val io = Executors.newSingleThreadExecutor()

    val tagColors = listOf(0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594, 0xFF0090FF, 0xFF6E56CF, 0xFFE93D82)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        mediaDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "media").apply { mkdirs() }
        avatarDir = File(mediaDir.parentFile, "avatars").apply { mkdirs() }
        val f = File(ctx.filesDir, "library.json")
        file = f
        val loaded = runCatching { fromJson(JSONObject(f.readText())) }.getOrDefault(LibState())
        // Anything still "in progress" belongs to a process that died.
        _state.value = loaded.copy(history = loaded.history.map {
            if (it.status == Status.PENDING || it.status == Status.RUNNING) it.copy(status = Status.FAILED, message = "interrupted") else it
        })
    }

    fun update(f: (LibState) -> LibState) {
        val s = _state.updateAndGet(f)
        io.execute { write(s) }
    }

    private fun write(s: LibState) {
        val target = file ?: return
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(toJson(s).toString())
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
    }

    fun fileOf(item: MediaItem) = File(mediaDir, item.file)

    /** A file name that does not exist yet in the media directory. */
    fun freshFile(name: String): File {
        val clean = name.replace(Regex("[^A-Za-z0-9._\\- \\[\\]()]"), "_").takeLast(120).ifBlank { "file" }
        var f = File(mediaDir, clean)
        var n = 1
        while (f.exists()) {
            f = File(mediaDir, "${clean.substringBeforeLast('.')}_${n++}.${clean.substringAfterLast('.', "bin")}")
        }
        return f
    }

    // ── items ──
    fun addItems(items: List<MediaItem>) = update { it.copy(items = items + it.items) }

    fun deleteItems(ids: Set<String>) {
        state.value.items.filter { it.id in ids }.forEach { fileOf(it).delete() }
        update { s -> s.copy(items = s.items.filterNot { it.id in ids }) }
    }

    fun setFavorite(ids: Set<String>, value: Boolean) =
        update { s -> s.copy(items = s.items.map { if (it.id in ids) it.copy(favorite = value) else it }) }

    fun setMemo(id: String, memo: String) =
        update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(memo = memo) else it }) }

    fun setTag(ids: Set<String>, tagId: String, on: Boolean) = update { s ->
        s.copy(items = s.items.map {
            if (it.id !in ids) it
            else if (on) it.copy(tags = (it.tags + tagId).distinct())
            else it.copy(tags = it.tags - tagId)
        })
    }

    // ── tags ──
    fun createTag(name: String): Tag {
        val existing = state.value.tags.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing
        val tag = Tag(UUID.randomUUID().toString(), name, tagColors[state.value.tags.size % tagColors.size])
        update { it.copy(tags = it.tags + tag) }
        return tag
    }

    fun deleteTag(tagId: String) = update { s ->
        s.copy(tags = s.tags.filterNot { it.id == tagId }, items = s.items.map { it.copy(tags = it.tags - tagId) })
    }

    // ── history ──
    fun addHistory(e: DownloadEntry) = update { it.copy(history = listOf(e) + it.history) }

    fun setHistory(id: String, status: Status, message: String = "", count: Int = 0) = update { s ->
        s.copy(history = s.history.map { if (it.id == id) it.copy(status = status, message = message, count = count) else it })
    }

    fun removeHistory(id: String) = update { s -> s.copy(history = s.history.filterNot { it.id == id }) }

    fun clearHistory() = update { s ->
        s.copy(history = s.history.filter { it.status == Status.PENDING || it.status == Status.RUNNING })
    }

    // ── profiles ──
    fun addProfile(p: Profile) = update { it.copy(profiles = listOf(p) + it.profiles) }

    fun updateProfile(id: String, f: (Profile) -> Profile) =
        update { s -> s.copy(profiles = s.profiles.map { if (it.id == id) f(it) else it }) }

    fun deleteProfiles(ids: Set<String>) = update { s -> s.copy(profiles = s.profiles.filterNot { it.id in ids }) }

    // ── JSON ──
    fun toJson(s: LibState): JSONObject = JSONObject().apply {
        put("version", 1)
        put("items", JSONArray().apply {
            s.items.forEach { i ->
                put(JSONObject().apply {
                    put("id", i.id); put("file", i.file); put("video", i.video); put("sourceUrl", i.sourceUrl)
                    put("platform", i.platform); put("username", i.username); put("title", i.title)
                    put("size", i.size); put("width", i.width); put("height", i.height); put("savedAt", i.savedAt)
                    put("favorite", i.favorite); put("memo", i.memo); put("tags", JSONArray(i.tags))
                    if (i.group.isNotEmpty()) { put("group", i.group); put("position", i.position) }
                })
            }
        })
        put("tags", JSONArray().apply {
            s.tags.forEach { t -> put(JSONObject().apply { put("id", t.id); put("name", t.name); put("color", t.color) }) }
        })
        put("history", JSONArray().apply {
            s.history.forEach { h ->
                put(JSONObject().apply {
                    put("id", h.id); put("url", h.url); put("status", h.status.name)
                    put("message", h.message); put("time", h.time); put("count", h.count)
                })
            }
        })
        put("profiles", JSONArray().apply {
            s.profiles.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("url", p.url); put("platform", p.platform); put("handle", p.handle)
                    put("name", p.name); put("avatar", p.avatar); put("savedAt", p.savedAt)
                })
            }
        })
    }

    fun fromJson(o: JSONObject): LibState {
        fun <T> arr(name: String, f: (JSONObject) -> T): List<T> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { idx -> runCatching { f(a.getJSONObject(idx)) }.getOrNull() }
        }
        return LibState(
            items = arr("items") { j ->
                val tags = j.optJSONArray("tags")
                MediaItem(
                    id = j.getString("id"), file = j.getString("file"), video = j.optBoolean("video"),
                    sourceUrl = j.optString("sourceUrl"), platform = j.optString("platform"),
                    username = j.optString("username"), title = j.optString("title"),
                    size = j.optLong("size"), width = j.optInt("width"), height = j.optInt("height"),
                    savedAt = j.optLong("savedAt"), favorite = j.optBoolean("favorite"), memo = j.optString("memo"),
                    tags = if (tags == null) emptyList() else (0 until tags.length()).map { tags.getString(it) },
                    group = j.optString("group"), position = j.optInt("position"),
                )
            },
            tags = arr("tags") { j -> Tag(j.getString("id"), j.getString("name"), j.getLong("color")) },
            history = arr("history") { j ->
                DownloadEntry(
                    j.getString("id"), j.optString("url"),
                    runCatching { Status.valueOf(j.optString("status")) }.getOrDefault(Status.FAILED),
                    j.optString("message"), j.optLong("time"), j.optInt("count"),
                )
            },
            profiles = arr("profiles") { j ->
                Profile(
                    id = j.getString("id"), url = j.getString("url"), platform = j.optString("platform"),
                    handle = j.optString("handle"), name = j.optString("name"), avatar = j.optString("avatar"),
                    savedAt = j.optLong("savedAt"),
                )
            },
        )
    }
}
