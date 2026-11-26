package com.synrg.harbor.episodes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.synrg.harbor.MainActivity
import com.synrg.harbor.R
import com.synrg.harbor.core.Strings
import com.synrg.harbor.data.Img
import com.synrg.harbor.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Notifications for new episodes: one per series, tapping opens its page. */
object EpisodeAlerts {
    private const val CHANNEL = "episodes"

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Creates the channel, or renames an existing one to the current language (importance stays as the user set it). */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, Strings.get(R.string.common_new_episodes), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = Strings.get(R.string.alerts_channel_description)
            },
        )
    }

    suspend fun post(context: Context, episodes: List<CalendarEpisode>) {
        if (episodes.isEmpty() || !permitted(context)) return
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        episodes.groupBy { it.tmdbId }.forEach { (tmdbId, list) ->
            val sorted = list.sortedWith(compareBy({ it.season }, { it.episode }))
            val first = sorted.first()
            val title = Strings.plural(R.plurals.alerts_new_episodes_title, sorted.size, sorted.size, first.show)
            val text = if (sorted.size == 1) {
                Strings.get(R.string.alerts_episode_label, first.season, first.episode) + (first.name?.let { " — $it" } ?: "")
            } else {
                sorted.joinToString(", ") { Strings.get(R.string.common_episode_code, it.season, it.episode) }
            }
            val open = PendingIntent.getActivity(
                context, tmdbId,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN)
                    .putExtra(MainActivity.EXTRA_TYPE, "tv")
                    .putExtra(MainActivity.EXTRA_ID, tmdbId)
                    .putExtra(MainActivity.EXTRA_SEASON, sorted.last().season)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_harbor)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setLargeIcon(poster(context, first.poster))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            try {
                manager.notify("episodes", tmdbId, notification)
            } catch (_: SecurityException) {
                // Permission withdrawn in the meantime.
            }
        }
    }

    private suspend fun poster(context: Context, path: String?): Bitmap? {
        val url = Img.poster(path, "w185") ?: return null
        return runCatching {
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            context.imageLoader.execute(request).image?.toBitmap()
        }.getOrNull()
    }
}

/** The background check: every few hours, with any network, also after a reboot. */
class EpisodeCheckJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = applicationContext.graph
        work = graph.scope.launch {
            val fresh = runCatching { graph.episodes.refresh(advance = true) }.getOrDefault(emptyList())
            if (graph.settings.current.episodeAlerts) EpisodeAlerts.post(applicationContext, fresh)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val ID = 4201
        private const val PERIOD_MS = 6 * 3600_000L

        fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                scheduler.cancel(ID)
                return
            }
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, EpisodeCheckJob::class.java))
                    .setPeriodic(PERIOD_MS, 3600_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
