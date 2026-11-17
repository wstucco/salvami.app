package org.salvami.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup archive = zip with `library.json` (items, tags, memos, history, profiles), `media/<files>` and `avatars/<files>`. */
object Backup {
    fun export(ctx: Context, uri: Uri): Int {
        val state = Library.state.value
        var count = 0
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(Library.toJson(state).toString(2).toByteArray())
                zip.closeEntry()
                zip.setLevel(Deflater.NO_COMPRESSION) // media is already compressed
                for (item in state.items) {
                    val f = Library.fileOf(item)
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry("media/${item.file}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
                for (p in state.profiles) {
                    val f = File(Library.avatarDir, p.avatar)
                    if (p.avatar.isEmpty() || !f.isFile) continue
                    zip.putNextEntry(ZipEntry("avatars/${p.avatar}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return count
    }

    /** Merges a backup into the current library; items already present (same id) are kept as they are. */
    fun restore(ctx: Context, uri: Uri): Int {
        var backup: LibState? = null
        val staging = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val media = File(staging, "media").apply { mkdirs() }
        val avatars = File(staging, "avatars").apply { mkdirs() }
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        // File(name).name drops any path component (zip-slip guard).
                        val dir = when {
                            e.name == "library.json" -> { backup = Library.fromJson(JSONObject(zip.readBytes().decodeToString())); continue }
                            e.name.startsWith("media/") -> media
                            e.name.startsWith("avatars/") -> avatars
                            else -> continue
                        }
                        File(dir, File(e.name).name).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val b = backup ?: throw IllegalArgumentException("library.json missing: not a Salvami backup")
            return merge(b, { name, dest -> File(media, name).takeIf { it.isFile }?.let { move(it, dest) } != null }) { name, dest ->
                File(avatars, name).takeIf { it.isFile }?.let { move(it, dest) } != null
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Like [restore], from a copy of the library folder (Android/media/org.salvami.app) that a
     * backup or sync app made: [tree] is the folder picked with the system's folder picker.
     */
    fun restoreFolder(ctx: Context, tree: Uri): Int {
        val cr = ctx.contentResolver
        fun children(docId: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.getString(1)] = c.getString(0)
            }
            return out
        }
        fun copy(docId: String, dest: File): Boolean = runCatching {
            cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        }.onFailure { dest.delete() }.isSuccess

        val top = children(DocumentsContract.getTreeDocumentId(tree))
        val json = top["library.json"] ?: throw IllegalArgumentException("library.json missing: not a Salvami folder")
        val b = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, json))!!.use { Library.fromJson(JSONObject(it.readBytes().decodeToString())) }
        val media = top["media"]?.let(::children).orEmpty()
        val avatars = top["avatars"]?.let(::children).orEmpty()
        return merge(b, { name, dest -> media[name]?.let { copy(it, dest) } ?: false }) { name, dest -> avatars[name]?.let { copy(it, dest) } ?: false }
    }

    private fun move(src: File, dest: File): File {
        if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
        return dest
    }

    /** Adds what [b] has and the library lacks; [media]/[avatar] put the named file at dest, false if missing. */
    private fun merge(b: LibState, media: (String, File) -> Boolean, avatar: (String, File) -> Boolean): Int {
        val current = Library.state.value
        val knownItems = current.items.map { it.id }.toSet()
        val newItems = b.items.filter { it.id !in knownItems }.mapNotNull { item ->
            val dest = Library.freshFile(item.file)
            if (media(item.file, dest)) item.copy(file = dest.name) else null
        }
        val knownProfiles = current.profiles.map { it.id }.toSet()
        val newProfiles = b.profiles.filter { it.id !in knownProfiles }.map { p ->
            if (p.avatar.isNotEmpty() && avatar(p.avatar, File(Library.avatarDir, p.avatar))) p else p.copy(avatar = "")
        }
        val knownTags = current.tags.map { it.id }.toSet()
        val knownHistory = current.history.map { it.id }.toSet()
        Library.update { s ->
            s.copy(
                items = (s.items + newItems).sortedByDescending { it.savedAt },
                tags = s.tags + b.tags.filter { it.id !in knownTags },
                history = (s.history + b.history.filter { it.id !in knownHistory }).sortedByDescending { it.time },
                profiles = (s.profiles + newProfiles).sortedByDescending { it.savedAt },
            )
        }
        return newItems.size
    }
}
