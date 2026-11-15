package org.salvami.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Foreground service that downloads queued links one at a time, also while the app is closed. */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val EXTRA_ID = "id"
        private const val EXTRA_URL = "url"

        /** Progress of the running downloads, by history id. */
        val progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
        private val cancelled: MutableSet<String> = Collections.synchronizedSet(HashSet())

        fun isCancelled(id: String) = id in cancelled

        fun enqueue(ctx: Context, url: String) {
            val id = UUID.randomUUID().toString()
            Library.addHistory(DownloadEntry(id, url, Status.PENDING, "", System.currentTimeMillis(), 0))
            val intent = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_URL, url)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun cancel(id: String) {
            cancelled += id
            Downloader.cancel(id)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch {
            for ((id, url) in queue) {
                process(id, url)
                if (pending.decrementAndGet() == 0) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(getString(R.string.downloading), "", -1), type)
        val id = intent?.getStringExtra(EXTRA_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (id != null && url != null) {
            pending.incrementAndGet()
            queue.trySend(id to url)
        } else if (pending.get() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun process(id: String, url: String) {
        if (isCancelled(id)) {
            Library.setHistory(id, Status.FAILED, getString(R.string.cancelled))
            return
        }
        Library.setHistory(id, Status.RUNNING)
        val nm = getSystemService(NotificationManager::class.java)
        var last: Pair<Progress.Phase, Int>? = null
        try {
            val items = Downloader.fetch(this, url, id) { p ->
                progress.update { it + (id to p) }
                val percent = if (p.phase == Progress.Phase.DOWNLOADING) (p.fraction * 100).toInt() else -1
                if (last != p.phase to percent) {
                    last = p.phase to percent
                    nm.notify(NOTIF_ID, notification(p.label(this), url, percent))
                }
            }
            Library.addItems(items)
            Library.setHistory(id, Status.COMPLETED, count = items.size)
        } catch (e: Throwable) {
            val msg = if (isCancelled(id)) getString(R.string.cancelled) else (e.message ?: e.javaClass.simpleName)
            Library.setHistory(id, Status.FAILED, msg.trim().takeLast(400))
        } finally {
            progress.update { it - id }
            cancelled -= id
        }
    }

    private fun notification(title: String, text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
    }
}
