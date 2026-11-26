package com.synrg.harbor.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.synrg.harbor.MainActivity
import com.synrg.harbor.R
import com.synrg.harbor.core.Strings
import com.synrg.harbor.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps downloads going with the app in the background, with their progress in a notification. */
class DownloadService : Service() {
    private var work: Job? = null
    private val downloads get() = applicationContext.graph.downloads

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (work?.isActive != true) {
            work = applicationContext.graph.scope.launch {
                try {
                    downloads.runQueue(onUpdate = ::refresh)
                } finally {
                    notifyFinished()
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /** Android 15 caps data-sync services at 6 h a day: stop cleanly, the queue resumes next time. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        work?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        work?.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, progressNotification()) }
    }

    private fun openDownloads() = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification() = NotificationCompat.Builder(this, CHANNEL_PROGRESS).apply {
        val items = downloads.list
        val current = items.firstOrNull { it.status == DownloadStatus.Downloading }
        val waiting = items.any { it.status == DownloadStatus.WaitingWifi }
        val queued = items.count { it.active }
        setSmallIcon(R.drawable.ic_stat_harbor)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setContentIntent(openDownloads())
        when {
            current != null -> {
                val speed = downloads.speed.value[current.id]?.bps ?: 0
                setContentTitle(Strings.get(R.string.notif_downloading, label(current)))
                setContentText(
                    listOfNotNull(
                        "${(current.fraction * 100).toInt()}%",
                        if (speed > 0) formatBytes(speed) + "/s" else null,
                        if (queued > 1) Strings.get(R.string.notif_queued, queued - 1) else null,
                    ).joinToString(" · "),
                )
                setProgress(1000, (current.fraction * 1000).toInt(), current.total <= 0)
            }
            waiting -> {
                setContentTitle(Strings.get(R.string.notif_waiting_title))
                setContentText(Strings.get(R.string.notif_waiting_text))
            }
            else -> setContentTitle(Strings.get(R.string.notif_preparing))
        }
    }.build()

    /** "Ready to watch offline", once per download finished in this run. */
    private fun notifyFinished() {
        val done = downloads.takeFinished()
        if (done.isEmpty()) return
        val text = if (done.size == 1) label(done.first()) else Strings.plural(R.plurals.notif_downloads_count, done.size, done.size)
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_harbor)
            .setContentTitle(Strings.get(R.string.notif_ready_title))
            .setContentText(text)
            .setContentIntent(openDownloads())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_DONE_ID, notification) }
    }

    /** Creates the channels, or renames existing ones to the current language (importance stays as the user set it). */
    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, Strings.get(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_DONE, Strings.get(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_DONE = "downloads-done"
        const val NOTIFICATION_ID = 7001
        const val NOTIFICATION_DONE_ID = 7002

        fun label(item: DownloadItem) =
            item.title + if (item.season != null && item.episode != null) " " + Strings.get(R.string.common_episode_code, item.season, item.episode) else ""
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Strings.locale, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Strings.locale, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
