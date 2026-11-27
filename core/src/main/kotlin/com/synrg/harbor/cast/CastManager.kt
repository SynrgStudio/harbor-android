package com.synrg.harbor.cast

import androidx.compose.runtime.Immutable
import com.synrg.harbor.player.SubtitleCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.net.URI

/** A place to play on: a Chromecast (by its route id) or a DLNA TV. */
@Immutable
sealed interface CastDevice {
    val id: String
    val name: String

    data class Chromecast(override val id: String, override val name: String) : CastDevice
    data class Tv(val device: DlnaDevice) : CastDevice {
        override val id: String get() = device.udn
        override val name: String get() = device.name
    }
}

/** What is playing on the TV, refreshed every second. */
@Immutable
data class CastState(
    val device: CastDevice,
    val connecting: Boolean = true,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val failed: Boolean = false,
)

/** What to send: where the video is, and how to present it. */
class CastMedia(
    /** A link the TV can fetch as it is (http(s), no special headers), or null to go through the phone. */
    val directUrl: String?,
    /** How the phone serves it otherwise: the torrent engine, a downloaded file, a link that needs headers. */
    val source: LanServer.Item,
    val mime: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val startMs: Long,
    val subtitles: List<SubtitleCue>?,
    val subtitleLanguage: String?,
)

/**
 * "Play on a TV": finds Chromecasts (Google Cast) and DLNA TVs on the Wi-Fi in one list, sends the
 * video and the subtitle, and mirrors what the TV is doing. Chromecasts fetch direct links
 * themselves; everything else (torrents, downloads, DLNA, which often can't do HTTPS) goes
 * through [LanServer] on the phone.
 */
class CastManager(
    http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Starts Chromecast support the first time TVs are searched; null where the platform has none. */
    private val openChromecasts: suspend () -> Chromecasts? = { null },
    /** See [Dlna]. */
    holdMulticast: () -> AutoCloseable? = { null },
    /** A TV description to list even if it doesn't answer the search (the fake TV, for the emulator). */
    private val extraTv: String? = null,
) {
    private val lan = LanServer(http)
    private val dlna = Dlna(http, holdMulticast)
    private var google: Chromecasts? = null
    private var googleTried = false

    private val dlnaDevices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    private val chromecasts = MutableStateFlow<List<Chromecasts.Route>>(emptyList())

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _state = MutableStateFlow<CastState?>(null)
    val state: StateFlow<CastState?> = _state

    private var discovery: Job? = null
    private var poll: Job? = null

    init {
        scope.launch {
            combine(chromecasts, dlnaDevices) { cc, tvs ->
                cc.map { CastDevice.Chromecast(it.id, it.name) } + tvs.map { CastDevice.Tv(it) }
            }.collect { _devices.value = it }
        }
    }

    /** Looks for devices while the picker is open: Cast keeps listening, DLNA asks again every few seconds. */
    fun discover() {
        if (discovery?.isActive == true) return
        discovery = scope.launch {
            _searching.value = true
            if (!googleTried) {
                googleTried = true
                google = openChromecasts()
            }
            google?.let { g ->
                g.startDiscovery()
                launch { g.routes.collect { chromecasts.value = it } }
            }
            while (isActive) {
                val found = runCatching { dlna.discover() }.getOrDefault(emptyList()) + listOfNotNull(devTv())
                // Kept for the session: a TV that answered once doesn't blink out of the list.
                dlnaDevices.update { old -> (old + found).distinctBy { it.udn } }
                _searching.value = false
                delay(8_000)
            }
        }
    }

    /** [extraTv], when it answers. */
    private suspend fun devTv(): DlnaDevice? =
        extraTv?.let { address -> withTimeoutOrNull(1_500) { runCatching { dlna.describe(address) }.getOrNull() } }

    fun stopDiscovery() {
        discovery?.cancel()
        discovery = null
        _searching.value = false
        scope.launch { google?.stopDiscovery() }
    }

    /** Sends [media] to [device]; true when the TV took it. */
    suspend fun start(device: CastDevice, media: CastMedia): Boolean {
        stopDiscovery()
        _state.value = CastState(device)
        val ok = runCatching {
            when (device) {
                is CastDevice.Chromecast -> {
                    val g = google ?: error("no cast")
                    g.connect(device.id)
                    val url = media.directUrl ?: publishVideo(media) ?: error("no wifi")
                    val vtt = media.subtitles?.let { lan.publish(LanServer.Item.Bytes(SubtitleText.vtt(it).toByteArray(), "text/vtt"), "vtt") }
                    g.load(url, media.mime, media.title, media.subtitle, media.image, media.startMs, vtt, media.subtitleLanguage)
                }
                is CastDevice.Tv -> {
                    val url = publishVideo(media) ?: error("no wifi")
                    val srt = media.subtitles?.let { lan.publish(LanServer.Item.Bytes(SubtitleText.srt(it).toByteArray(), "application/x-subrip"), "srt") }
                    dlna.load(device.device, url, media.title, media.mime, srt)
                    dlna.play(device.device)
                    if (media.startMs > 5_000) {
                        // Many TVs only take a seek once they are playing.
                        for (attempt in 1..10) {
                            delay(1_000)
                            if (runCatching { dlna.state(device.device) }.getOrNull() == "PLAYING") {
                                runCatching { dlna.seek(device.device, media.startMs) }
                                break
                            }
                        }
                    }
                }
            }
        }.isSuccess
        if (!ok) {
            _state.update { it?.copy(connecting = false, failed = true) }
            return false
        }
        _state.update { it?.copy(connecting = false, loading = true, positionMs = media.startMs) }
        startPolling(device)
        return true
    }

    private fun publishVideo(media: CastMedia): String? {
        val ext = when (media.mime) {
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            else -> "mp4"
        }
        return lan.publish(media.source, ext)
    }

    private fun startPolling(device: CastDevice) {
        poll?.cancel()
        poll = scope.launch {
            var failures = 0
            while (isActive) {
                delay(1_000)
                val update = runCatching {
                    when (device) {
                        is CastDevice.Chromecast -> google?.status()?.let { s ->
                            CastState(device, connecting = false, playing = s.playing, loading = s.loading, positionMs = s.positionMs, durationMs = s.durationMs, failed = s.failed)
                        }
                        is CastDevice.Tv -> {
                            val pos = dlna.position(device.device)
                            val st = dlna.state(device.device)
                            val previous = _state.value
                            CastState(
                                device, connecting = false, playing = st == "PLAYING", loading = st == "TRANSITIONING",
                                positionMs = pos?.first ?: previous?.positionMs ?: 0, durationMs = pos?.second?.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
                            )
                        }
                    }
                }.getOrNull()
                if (update == null) {
                    if (++failures >= 5) _state.update { it?.copy(failed = true) }
                    continue
                }
                failures = 0
                if (_state.value?.device == device) _state.value = update
            }
        }
    }

    fun togglePause() {
        val s = _state.value ?: return
        _state.value = s.copy(playing = !s.playing)
        scope.launch {
            runCatching {
                when (val d = s.device) {
                    is CastDevice.Chromecast -> if (s.playing) google?.pause() else google?.play()
                    is CastDevice.Tv -> if (s.playing) dlna.pause(d.device) else dlna.play(d.device)
                }
            }
        }
    }

    fun seek(ms: Long) {
        val s = _state.value ?: return
        val target = ms.coerceIn(0, if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE)
        _state.value = s.copy(positionMs = target)
        scope.launch {
            runCatching {
                when (val d = s.device) {
                    is CastDevice.Chromecast -> google?.seek(target)
                    is CastDevice.Tv -> dlna.seek(d.device, target)
                }
            }
        }
    }

    /** Stops the TV and the phone's server; returns where it was, to carry on on the phone. */
    suspend fun stop(): Long {
        val s = _state.value ?: return 0
        poll?.cancel()
        _state.value = null
        runCatching {
            when (val d = s.device) {
                is CastDevice.Chromecast -> google?.end()
                is CastDevice.Tv -> dlna.stop(d.device)
            }
        }
        lan.stop()
        return s.positionMs
    }

    companion object {
        /** The MIME type TVs expect for a file name or link. */
        fun mimeFor(name: String): String = when (name.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            // MOV is MP4's older sibling: TVs play it when told it is MP4.
            else -> "video/mp4"
        }

        fun localFile(url: String): File? {
            if (!url.startsWith("file:")) return null
            // Saved as "file://" + path, which may hold characters a strict URI rejects.
            val path = runCatching { URI(url).path }.getOrNull() ?: url.removePrefix("file://")
            return File(path)
        }
    }
}
