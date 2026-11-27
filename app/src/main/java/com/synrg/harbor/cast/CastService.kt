package com.synrg.harbor.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.synrg.harbor.R
import com.synrg.harbor.core.Strings
import com.synrg.harbor.graph
import com.synrg.harbor.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * While something plays on a TV: keeps the phone awake enough to serve it (the TV reads the video
 * from the phone's Wi-Fi server; a sleeping phone would cut it), with a notification to pause or
 * stop from anywhere.
 */
class CastService : Service() {
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cast = applicationContext.graph.cast
        when (intent?.action) {
            ACTION_TOGGLE -> cast.togglePause()
            ACTION_STOP -> {
                applicationContext.graph.scope.launch { cast.stop() }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, Strings.get(R.string.cast_channel), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(cast.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harbor:cast").apply { acquire(6 * 3_600_000L) }
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "harbor:cast").apply { acquire() }
        }
        if (watch?.isActive != true) {
            watch = applicationContext.graph.scope.launch {
                cast.state.map { it?.let { s -> Triple(s.device.name, s.playing, s.connecting) } }.distinctUntilChanged().collect { key ->
                    if (key == null) {
                        stopSelf()
                    } else {
                        runCatching { NotificationManagerCompat.from(this@CastService).notify(NOTIFICATION_ID, notification(cast.state.value)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    private fun notification(state: CastState?) = NotificationCompat.Builder(this, CHANNEL).apply {
        setSmallIcon(R.drawable.ic_stat_harbor)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setContentTitle(Strings.get(R.string.cast_playing_on, state?.device?.name.orEmpty()))
        setContentIntent(
            PendingIntent.getActivity(
                this@CastService, 0,
                Intent(this@CastService, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        addAction(
            0,
            Strings.get(if (state?.playing == true) R.string.player_pause else R.string.player_play),
            service(ACTION_TOGGLE, 1),
        )
        addAction(0, Strings.get(R.string.cast_stop), service(ACTION_STOP, 2))
    }.build()

    private fun service(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, CastService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_TOGGLE = "com.synrg.harbor.cast.TOGGLE"
        private const val ACTION_STOP = "com.synrg.harbor.cast.STOP"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, CastService::class.java))
    }
}
