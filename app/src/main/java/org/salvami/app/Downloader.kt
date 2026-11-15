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
    /** yt-dlp errors after which the page's og:image is the real content (photo posts, unsupported sites). */
    private val PAGE_IMAGE_OK = Regex("no video|unsupported url", RegexOption.IGNORE_CASE)
    /** yt-dlp found the post but no video in (some of) it: photos, e.g. an Instagram carousel. */
    private val NO_VIDEO = Regex("no video formats found", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value }

    private fun isMedia(f: File) = f.extension.lowercase().let { it in VIDEO_EXT || it in IMAGE_EXT }

    /**
     * Downloads every media file behind [url] and returns the new library items.
     * yt-dlp does the heavy lifting; plain image/video links and pages exposing
     * og:video / og:image are handled by a small fallback. og:image is only used when yt-dlp
     * says there is no video: for any other failure it is just a thumbnail of what we missed.
     */
    suspend fun fetch(ctx: Context, url: String, jobId: String, onProgress: (Float) -> Unit): List<MediaItem> {
        val tmp = File(ctx.cacheDir, "job-$jobId").apply { deleteRecursively(); mkdirs() }
        try {
            var ytError: Throwable? = App.ytReady.await()
            if (ytError == null) {
                // The service may outlive a day; downloads run one at a time, so this is a safe spot.
                App.updateYtDlpIfDue(ctx)
                ytError = ytDlp(url, tmp, jobId, photos = false, onProgress)
                if (!DownloadService.isCancelled(jobId)) Muxer.finish(tmp)
                // Photos come as the entries' thumbnails, at full resolution. Ask for them in a second
                // pass only when needed, or every video would also bring along its cover image.
                if (ytError != null && NO_VIDEO.containsMatchIn(ytError.message.orEmpty()) && !DownloadService.isCancelled(jobId)) {
                    val videos = mediaIn(tmp).map { it.nameWithoutExtension }.toSet()
                    val before = mediaIn(tmp).toSet()
                    ytDlp(url, tmp, jobId, photos = true, onProgress)
                    // In a mixed carousel the videos are already there: drop their covers.
                    (mediaIn(tmp) - before).filter { it.nameWithoutExtension in videos }.forEach { it.delete() }
                    if (mediaIn(tmp).isNotEmpty()) ytError = null
                }
            }
            if (DownloadService.isCancelled(jobId)) throw IllegalStateException("cancelled")

            var files: List<File> = mediaIn(tmp)
            if (files.isEmpty()) {
                val pageImage = ytError == null || PAGE_IMAGE_OK.containsMatchIn(ytError.message.orEmpty())
                val direct = runCatching { fallback(url, tmp, pageImage) }.getOrNull()
                if (direct == null) throw IllegalStateException(ytError?.let(::errorText) ?: "No media found")
                files = listOf(direct)
            }

            val host = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            val infos = files.associateWith { f -> runCatching { JSONObject(File(tmp, f.nameWithoutExtension + ".info.json").readText()) }.getOrNull() }
            // Keep the order of the post (playlist_index), not the alphabetical one of the file names.
            val ordered = files.sortedWith(compareBy<File> { infos[it]?.optInt("playlist_index", Int.MAX_VALUE) ?: Int.MAX_VALUE }.thenBy { it.name })
            val items = ordered.map { f ->
                val info = infos[f]
                fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> info?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } }
                val dest = Library.freshFile(f.name)
                if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
                describe(
                    dest,
                    sourceUrl = s("webpage_url") ?: url,
                    platform = s("extractor_key") ?: host,
                    username = s("uploader", "channel", "uploader_id") ?: "",
                    // Instagram names every entry "Video by …", photos included.
                    title = (s("title") ?: dest.nameWithoutExtension)
                        .let { if (dest.extension.lowercase() in IMAGE_EXT) it.replaceFirst(Regex("^Video by "), "Post by ") else it },
                )
            }
            return if (items.size < 2) items else items.mapIndexed { i, item -> item.copy(group = jobId, position = i) }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun mediaIn(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && isMedia(it) }.orEmpty()

    /** Runs yt-dlp into [dir]; with [photos] it saves each entry's thumbnail instead of the video. Returns the failure, if any. */
    private fun ytDlp(url: String, dir: File, jobId: String, photos: Boolean, onProgress: (Float) -> Unit): Throwable? {
        val req = YoutubeDLRequest(url)
        req.addOption("-o", "${dir.absolutePath}/%(title).60B [%(id)s].%(ext)s")
        req.addOption("--restrict-filenames")
        req.addOption("--no-playlist")
        req.addOption("--playlist-end", "30")
        req.addOption("--write-info-json")
        req.addOption("--no-mtime")
        if (photos) {
            req.addOption("--ignore-no-formats-error")
            req.addOption("--skip-download")
            req.addOption("--write-thumbnail")
        } else {
            // No ffmpeg: Muxer merges the streams, which needs MP4+M4A (or WebM+WebM).
            // H.264 first, as AV1/VP9 do not decode in hardware on many phones.
            req.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*[ext=webm]+ba[ext=webm]/b")
            req.addOption("-S", "vcodec:h264,res,acodec:aac")
        }
        return try {
            YoutubeDL.getInstance().execute(req, jobId) { percent, _, _ -> onProgress((percent / 100f).coerceIn(0f, 1f)) }
            null
        } catch (e: Throwable) {
            Log.w("Salvami", "yt-dlp failed for $url (photos=$photos)", e)
            e
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

    /** yt-dlp output is mostly warnings; keep the ERROR lines that say what went wrong. */
    private fun errorText(e: Throwable): String {
        val msg = e.message.orEmpty()
        return msg.lines().filter { it.startsWith("ERROR:") }.joinToString("\n").ifBlank { msg }
            .removePrefix("ERROR: ").trim()
    }

    private fun fallback(url: String, dir: File, pageImage: Boolean, depth: Int = 0): File? {
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
            val props = listOf("og:video:secure_url", "og:video") + if (pageImage) listOf("og:image") else emptyList()
            for (prop in props) {
                val m = Regex("""<meta[^>]+property=["']$prop["'][^>]+content=["']([^"']+)["']""").find(html)
                    ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$prop["']""").find(html)
                val target = m?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
                fallback(URL(URL(url), target).toString(), dir, pageImage, depth + 1)?.let { return it }
            }
            return null
        } finally {
            c.disconnect()
        }
    }
}
