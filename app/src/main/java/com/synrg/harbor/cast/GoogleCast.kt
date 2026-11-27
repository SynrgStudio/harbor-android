package com.synrg.harbor.cast

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cast's settings: Google's default media receiver, and no notification of its own (CastService has one). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast and Google TV, through Google's Cast library (it needs Google Play services). It is
 * only started the first time the user opens "Play on a TV"; on phones without Play services
 * [create] returns null and only DLNA TVs are listed.
 */
class GoogleCast private constructor(private val cast: CastContext, private val router: MediaRouter) {
    class Route(val id: String, val name: String)

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes

    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private fun refresh() {
        _routes.value = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }.map { Route(it.id, it.name) }
    }

    suspend fun startDiscovery() = withContext(Dispatchers.Main) {
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
    }

    suspend fun stopDiscovery() = withContext(Dispatchers.Main) { router.removeCallback(callback) }

    /** Connects to the Chromecast and waits for the session (Cast starts the receiver app on the TV). */
    suspend fun connect(routeId: String): Unit = withContext(Dispatchers.Main) {
        val route = router.routes.firstOrNull { it.id == routeId } ?: throw IOException("gone")
        val manager = cast.sessionManager
        manager.currentCastSession?.takeIf { it.isConnected }?.let { return@withContext }
        val started = withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SessionManagerListener<CastSession> {
                    override fun onSessionStarted(session: CastSession, sessionId: String) = done(null)
                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = done(null)
                    override fun onSessionStartFailed(session: CastSession, error: Int) = done(IOException("Cast error $error"))
                    override fun onSessionStarting(session: CastSession) = Unit
                    override fun onSessionEnding(session: CastSession) = Unit
                    override fun onSessionEnded(session: CastSession, error: Int) = Unit
                    override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                    override fun onSessionResumeFailed(session: CastSession, error: Int) = done(IOException("Cast error $error"))
                    override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

                    fun done(error: Throwable?) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        if (!cont.isActive) return
                        if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
                    }
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation { manager.removeSessionManagerListener(listener, CastSession::class.java) }
                router.selectRoute(route)
            }
        }
        if (started == null) throw IOException("timeout")
    }

    private val client: RemoteMediaClient? get() = cast.sessionManager.currentCastSession?.remoteMediaClient

    suspend fun load(url: String, mime: String, title: String, subtitle: String?, image: String?, startMs: Long, vttUrl: String?, vttLanguage: String?) =
        withContext(Dispatchers.Main) {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
                image?.let { addImage(WebImage(Uri.parse(it))) }
            }
            val tracks = vttUrl?.let {
                listOf(
                    MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(it)
                        .setContentType("text/vtt")
                        .setLanguage(vttLanguage ?: "und")
                        .setName(vttLanguage ?: "Subtitles")
                        .build(),
                )
            }
            val info = MediaInfo.Builder(url)
                .setContentUrl(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mime)
                .setMetadata(metadata)
                .apply { if (tracks != null) setMediaTracks(tracks) }
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(startMs)
                .apply { if (tracks != null) setActiveTrackIds(longArrayOf(1)) }
                .build()
            (client ?: throw IOException("no session")).load(request)
        }

    /** Position, duration, playing and loading, as the Chromecast last reported them. */
    suspend fun status(): Status? = withContext(Dispatchers.Main) {
        val c = client ?: return@withContext null
        Status(c.approximateStreamPosition, c.streamDuration, c.isPlaying, c.isBuffering || c.isLoadingNextItem, c.mediaStatus?.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR)
    }

    class Status(val positionMs: Long, val durationMs: Long, val playing: Boolean, val loading: Boolean, val failed: Boolean)

    suspend fun play() = withContext(Dispatchers.Main) { client?.play() }

    suspend fun pause() = withContext(Dispatchers.Main) { client?.pause() }

    suspend fun seek(ms: Long) = withContext(Dispatchers.Main) { client?.seek(MediaSeekOptions.Builder().setPosition(ms).build()) }

    suspend fun end() = withContext(Dispatchers.Main) {
        runCatching { client?.stop() }
        cast.sessionManager.endCurrentSession(true)
    }

    companion object {
        /** Starts Cast; null without Google Play services (or when Cast can't start). */
        suspend fun create(context: Context): GoogleCast? = withContext(Dispatchers.Main) {
            runCatching {
                val cast = CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context)).await()
                GoogleCast(cast, MediaRouter.getInstance(context))
            }.getOrNull()
        }

        private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}
