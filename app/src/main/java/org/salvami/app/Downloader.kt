package org.salvami.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object Downloader {
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "flv")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "bmp")
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val URL_RE = Regex("""https?://[^\s<>"']+""")

    fun firstUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value }

    private fun isMedia(f: File) = f.extension.lowercase().let { it in VIDEO_EXT || it in IMAGE_EXT }

    /**
     * Downloads every media file behind [url] and returns the new library items.
     * yt-dlp does the heavy lifting; plain image/video links and pages exposing
     * og:video / og:image are handled by a small fallback.
     */
    suspend fun fetch(ctx: Context, url: String, jobId: String, onProgress: (Float) -> Unit): List<MediaItem> {
        val tmp = File(ctx.cacheDir, "job-$jobId").apply { deleteRecursively(); mkdirs() }
        try {
            var ytError: Throwable? = App.ytReady.await()
            if (ytError == null) {
                // The service may outlive a day; downloads run one at a time, so this is a safe spot.
                App.updateYtDlpIfDue(ctx)
                try {
                    val req = YoutubeDLRequest(url)
                    req.addOption("-o", "${tmp.absolutePath}/%(title).60B [%(id)s].%(ext)s")
                    req.addOption("--restrict-filenames")
                    req.addOption("--no-playlist")
                    req.addOption("--playlist-end", "30")
                    req.addOption("--write-info-json")
                    req.addOption("--no-mtime")
                    req.addOption("-S", "ext:mp4:m4a")
                    YoutubeDL.getInstance().execute(req, jobId) { percent, _, _ -> onProgress((percent / 100f).coerceIn(0f, 1f)) }
                } catch (e: Throwable) {
                    Log.w("Salvami", "yt-dlp failed for $url", e)
                    ytError = e
                }
            }
            if (DownloadService.isCancelled(jobId)) throw IllegalStateException("cancelled")

            var files: List<File> = tmp.listFiles()?.filter { it.isFile && isMedia(it) }.orEmpty()
            if (files.isEmpty()) {
                val direct = runCatching { fallback(url, tmp) }.getOrNull()
                if (direct == null) throw (ytError ?: IllegalStateException("No media found"))
                files = listOf(direct)
            }

            val host = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            return files.sortedBy { it.name }.map { f ->
                val info = runCatching { JSONObject(File(tmp, f.nameWithoutExtension + ".info.json").readText()) }.getOrNull()
                fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> info?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } }
                val dest = Library.freshFile(f.name)
                if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
                describe(
                    dest,
                    sourceUrl = s("webpage_url") ?: url,
                    platform = s("extractor_key") ?: host,
                    username = s("uploader", "channel", "uploader_id") ?: "",
                    title = s("title") ?: dest.nameWithoutExtension,
                )
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun cancel(jobId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(jobId) }
    }

    /** Builds a library item for a file that is already inside the media directory. */
    fun describe(file: File, sourceUrl: String, platform: String, username: String, title: String): MediaItem {
        val video = file.extension.lowercase() in VIDEO_EXT
        var w = 0
        var h = 0
        runCatching {
            if (video) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                } finally {
                    r.release()
                }
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, o)
                w = o.outWidth.coerceAtLeast(0)
                h = o.outHeight.coerceAtLeast(0)
            }
        }
        return MediaItem(
            id = UUID.randomUUID().toString(), file = file.name, video = video, sourceUrl = sourceUrl,
            platform = platform, username = username, title = title, size = file.length(),
            width = w, height = h, savedAt = System.currentTimeMillis(),
        )
    }

    /** Copies photos/videos picked from the device into the library. */
    fun importLocal(ctx: Context, uris: List<Uri>): Int {
        val items = uris.mapNotNull { uri ->
            runCatching {
                var name = "import"
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                if (!name.contains('.')) {
                    val mime = ctx.contentResolver.getType(uri).orEmpty()
                    name += if (mime.startsWith("video")) ".mp4" else ".jpg"
                }
                val dest = Library.freshFile(name)
                ctx.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
                describe(dest, sourceUrl = "", platform = "local", username = "", title = dest.nameWithoutExtension)
            }.getOrNull()
        }
        if (items.isNotEmpty()) Library.addItems(items)
        return items.size
    }

    // ── fallback: direct media links and og: meta tags ──
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun fallback(url: String, dir: File, depth: Int = 0): File? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return null
            val type = c.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (type.startsWith("image/") || type.startsWith("video/")) {
                val ext = when (type) {
                    "image/jpeg" -> "jpg"
                    "video/quicktime" -> "mov"
                    else -> type.substringAfter('/').takeIf { it in VIDEO_EXT || it in IMAGE_EXT } ?: if (type.startsWith("video/")) "mp4" else "jpg"
                }
                val base = URL(url).path.substringAfterLast('/').substringBeforeLast('.').take(60).ifBlank { "media" }
                val out = File(dir, "$base.$ext")
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                return out
            }
            if (depth > 0 || !type.contains("html")) return null
            val html = c.inputStream.use { input ->
                val buf = ByteArray(1_500_000)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n)
            }
            for (prop in listOf("og:video:secure_url", "og:video", "og:image")) {
                val m = Regex("""<meta[^>]+property=["']$prop["'][^>]+content=["']([^"']+)["']""").find(html)
                    ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$prop["']""").find(html)
                val target = m?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
                fallback(URL(URL(url), target).toString(), dir, depth + 1)?.let { return it }
            }
            return null
        } finally {
            c.disconnect()
        }
    }
}
