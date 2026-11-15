package org.salvami.app

import android.app.Application
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlin.concurrent.thread

class App : Application(), ImageLoaderFactory {
    companion object {
        /** Completes with null when yt-dlp is ready, or with the error that prevented it. */
        val ytReady = CompletableDeferred<Throwable?>()

        /** yt-dlp ships fixes for broken sites every few days; an old copy is the usual cause of failures. */
        private const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L

        /**
         * Updates yt-dlp to the latest stable release, unless [force] is false and the last
         * successful check is less than a day old. Call it only while no download is running.
         */
        @Synchronized
        fun updateYtDlp(ctx: Context, force: Boolean): YoutubeDL.UpdateStatus? {
            val prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("checked", 0) < UPDATE_EVERY_MS) return null
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("checked", now).apply()
            Log.i("Salvami", "yt-dlp update: $status (${YoutubeDL.getInstance().versionName(ctx)})")
            return status
        }

        /** Like [updateYtDlp] without force, but a failure (e.g. offline) never blocks a download. */
        fun updateYtDlpIfDue(ctx: Context) {
            runCatching { updateYtDlp(ctx, force = false) }
                .onFailure { Log.w("Salvami", "yt-dlp update check failed", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        // The first run unpacks Python + yt-dlp: keep it off the main thread.
        thread(name = "yt-init") {
            try {
                dropStaleYtDlp()
                YoutubeDL.getInstance().init(this)
                updateYtDlpIfDue(this)
                ytReady.complete(null)
            } catch (e: Throwable) {
                ytReady.complete(e)
            }
        }
    }

    /**
     * youtubedl-android only unpacks its bundled yt-dlp when none is on disk, so after an app
     * update the old copy would keep running. Drop it once per install so the bundled one wins.
     */
    private fun dropStaleYtDlp() {
        val installed = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val prefs = getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (prefs.getLong("installed", 0) == installed) return
        File(noBackupFilesDir, "youtubedl-android/yt-dlp").deleteRecursively()
        // The library remembers the version it last downloaded and would skip the update check.
        getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit()
            .remove("dlpVersion").remove("dlpVersionName").apply()
        // The bundled copy may be older than the one we had: check for updates right away.
        prefs.edit().putLong("installed", installed).remove("checked").apply()
    }

    // Thumbnails for videos as well as images.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
}
