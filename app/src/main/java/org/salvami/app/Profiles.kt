package org.salvami.app

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Log
import org.salvami.app.ui.toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** A bookmarked account: shared profile links are kept here instead of being downloaded. */
data class Profile(
    val id: String,
    val url: String,
    val platform: String,
    /** As in the link, without the leading "@". */
    val handle: String,
    /** Display name from the profile page; "" until fetched. */
    val name: String = "",
    /** File name inside Library.avatarDir; "" until fetched. */
    val avatar: String = "",
    val savedAt: Long,
)

/** A profile recognised from the shape of its link, with the link in canonical form. */
data class ProfileLink(val platform: String, val handle: String, val url: String)

object Profiles {
    private val INSTAGRAM_HANDLE = Regex("[A-Za-z0-9._]{1,30}")
    private val X_HANDLE = Regex("[A-Za-z0-9_]{1,15}")
    private val FACEBOOK_HANDLE = Regex("[A-Za-z0-9.\\-]{2,80}")
    private val INSTAGRAM_RESERVED = setOf("p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct", "about", "developer", "legal", "privacy", "terms", "web")
    private val X_RESERVED = setOf("home", "explore", "search", "i", "settings", "messages", "notifications", "compose", "intent", "share", "hashtag", "login", "signup", "tos", "privacy")
    private val FACEBOOK_RESERVED = setOf(
        "watch", "reel", "reels", "share", "groups", "events", "marketplace", "login", "gaming", "help", "pages", "photo",
        "photos", "videos", "stories", "people", "friends", "notifications", "messages", "bookmarks", "search", "hashtag",
    )
    private val YOUTUBE_TABS = setOf("featured", "videos", "shorts", "streams", "playlists", "community", "posts", "about", "releases", "podcasts")

    /** Link-preview crawlers get the og: tags without the login wall Instagram and Facebook show to browsers. */
    private const val PREVIEW_UA = "facebookexternalhit/1.1"
    private val io = Executors.newSingleThreadExecutor()

    /** The profile [url] points to, or null for posts, videos and anything else. */
    fun parse(url: String): ProfileLink? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")?.removePrefix("web.") ?: return null
        val segs = uri.pathSegments.filter { it.isNotEmpty() }
        val one = segs.singleOrNull()
        return when (host) {
            "instagram.com" -> one?.takeIf { it.lowercase() !in INSTAGRAM_RESERVED && INSTAGRAM_HANDLE.matches(it) }
                ?.let { ProfileLink("Instagram", it, "https://www.instagram.com/$it/") }
            "tiktok.com" -> one?.takeIf { it.length > 1 && it.startsWith("@") }?.drop(1)
                ?.let { ProfileLink("TikTok", it, "https://www.tiktok.com/@$it") }
            "x.com", "twitter.com" -> one?.takeIf { it.lowercase() !in X_RESERVED && X_HANDLE.matches(it) }
                ?.let { ProfileLink("X", it, "https://x.com/$it") }
            "youtube.com" -> when {
                segs.isNotEmpty() && segs[0].length > 1 && segs[0].startsWith("@") && (segs.size == 1 || segs.size == 2 && segs[1] in YOUTUBE_TABS) ->
                    segs[0].drop(1).let { ProfileLink("YouTube", it, "https://www.youtube.com/@$it") }
                segs.size in 2..3 && segs[0] in setOf("channel", "c", "user") && (segs.size == 2 || segs[2] in YOUTUBE_TABS) ->
                    ProfileLink("YouTube", segs[1], "https://www.youtube.com/${segs[0]}/${segs[1]}")
                else -> null
            }
            "facebook.com" -> when {
                segs == listOf("profile.php") -> uri.getQueryParameter("id")?.takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }
                    ?.let { ProfileLink("Facebook", it, "https://www.facebook.com/profile.php?id=$it") }
                else -> one?.takeIf { it.lowercase() !in FACEBOOK_RESERVED && !it.endsWith(".php") && FACEBOOK_HANDLE.matches(it) }
                    ?.let { ProfileLink("Facebook", it, "https://www.facebook.com/$it") }
            }
            else -> null
        }
    }

    /** What a shared or pasted link turns into: a saved profile, or otherwise a download. */
    fun take(ctx: Context, url: String) {
        if (save(url)) toast(ctx, ctx.getString(R.string.profile_saved))
        else {
            DownloadService.enqueue(ctx, url)
            toast(ctx, ctx.getString(R.string.queued))
        }
    }

    /** Saves [url] if it is a profile (once: saving it again just refreshes it). False for anything else. */
    fun save(url: String): Boolean {
        val link = parse(url) ?: return false
        val existing = Library.state.value.profiles.firstOrNull { it.platform == link.platform && it.handle.equals(link.handle, ignoreCase = true) }
        val profile = existing ?: Profile(UUID.randomUUID().toString(), link.url, link.platform, link.handle, savedAt = System.currentTimeMillis())
            .also { Library.addProfile(it) }
        refresh(profile.id)
        return true
    }

    /** Fetches name and picture in the background; best effort, the handle is enough to show the profile. */
    fun refresh(id: String) = io.execute {
        val p = Library.state.value.profiles.firstOrNull { it.id == id } ?: return@execute
        runCatching {
            val html = get(p.url) { it.readNBytes(2_000_000).decodeToString() }
            val name = meta(html, "og:title")?.let(::cleanName)
            val avatar = meta(html, "og:image")?.let { image ->
                // A new name each time, so the image cache does not keep showing the old picture.
                val file = File(Library.avatarDir, "${p.id}-${System.currentTimeMillis()}.jpg")
                get(URL(URL(p.url), image).toString()) { input -> file.outputStream().use { input.copyTo(it) } }
                file.name
            }
            Library.updateProfile(id) { cur ->
                if (avatar != null && cur.avatar.isNotEmpty()) File(Library.avatarDir, cur.avatar).delete()
                cur.copy(name = name ?: cur.name, avatar = avatar ?: cur.avatar)
            }
        }.onFailure { Log.w("Salvami", "profile refresh failed for ${p.url}", it) }
    }

    private fun <T> get(url: String, read: (java.io.InputStream) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", PREVIEW_UA)
            check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }

    private fun meta(html: String, prop: String): String? {
        val m = Regex("""<meta[^>]+property=["']$prop["'][^>]+content=["']([^"']+)["']""").find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$prop["']""").find(html)
        return m?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.takeIf { it.isNotEmpty() }
    }

    /** "NASA (@nasa) • Instagram photos and videos", "NASA on TikTok" → "NASA". */
    private fun cleanName(title: String): String? = title
        .replace(Regex("""\s*\(@[^)]*\).*$"""), "")
        .replace(Regex("""\s+on (TikTok|X|Instagram)$"""), "")
        .trim().takeIf { it.isNotEmpty() }

    fun delete(ids: Set<String>) {
        Library.state.value.profiles.filter { it.id in ids && it.avatar.isNotEmpty() }.forEach { File(Library.avatarDir, it.avatar).delete() }
        Library.deleteProfiles(ids)
    }
}
