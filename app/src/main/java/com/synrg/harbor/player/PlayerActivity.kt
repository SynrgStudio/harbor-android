package com.synrg.harbor.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Immutable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.TrackPref
import com.synrg.harbor.graph
import com.synrg.harbor.stremio.AddonStreams
import com.synrg.harbor.stremio.StreamKind
import com.synrg.harbor.stremio.StreamOption
import com.synrg.harbor.stremio.StremioSubtitle
import com.synrg.harbor.torrent.TorrentStats
import com.synrg.harbor.torrent.TorrentStream
import com.synrg.harbor.ui.theme.HarborTheme
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

@Immutable
data class PlayerStatus(
    val request: PlaybackRequest? = null,
    val preparing: String? = null,
    val error: String? = null,
    val torrent: TorrentStats? = null,
    val externalSubs: List<StremioSubtitle>? = null,
    val loadingSubs: Boolean = false,
    val hasNext: Boolean = false,
    /** The next episode with its source already chosen (and its torrent warming up). */
    val nextUp: PlaybackRequest? = null,
    val ended: Boolean = false,
    val pip: Boolean = false,
)

class PlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA = "request"
        private const val PREFETCH_BEFORE_END_MS = 180_000L

        fun intent(context: Context, request: PlaybackRequest, json: Json): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA, json.encodeToString(PlaybackRequest.serializer(), request))
    }

    private val graph get() = applicationContext.graph
    private lateinit var player: ExoPlayer
    private lateinit var httpFactory: OkHttpDataSource.Factory

    private val _status = MutableStateFlow(PlayerStatus())
    val status: StateFlow<PlayerStatus> = _status

    private var loadJob: Job? = null
    private var statsJob: Job? = null
    private var progressJob: Job? = null
    private var prefetchJob: Job? = null
    private var torrentId: Int? = null
    private var preparedTorrent: Pair<String, TorrentStream>? = null
    private var pendingSubtitleId: String? = null
    private var autoSubtitlesDone = false
    private var subtitleLanguage = ""
    private var userPickedText = false
    private var advancing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        player = buildPlayer()
        setContent {
            HarborTheme {
                PlayerScreen(player = player, controller = this)
            }
        }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveProgress()
        load(intent)
    }

    private fun buildPlayer(): ExoPlayer {
        httpFactory = OkHttpDataSource.Factory(graph.mediaHttp)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val renderers = DefaultRenderersFactory(this)
            // Hardware decoders first; FFmpeg only for what the phone cannot decode (DTS, TrueHD...).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25_000, 90_000, 1_500, 3_000)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
            .apply { addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) _status.update { it.copy(preparing = null, ended = false) }
            if (state == Player.STATE_ENDED) {
                saveProgress(ended = true)
                // The UI shows the next-episode countdown; with nothing next, just stop here.
                _status.update { it.copy(ended = true) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
            updatePipParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Se cortó la conexión con la fuente."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "La fuente ya no está disponible (enlace vencido o caído)."
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "El teléfono no puede decodificar este video. Probá otra fuente."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Formato de archivo no soportado."
                else -> error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
            }
            _status.update { it.copy(error = message, preparing = null) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

        override fun onTracksChanged(tracks: Tracks) {
            if (!autoSubtitlesDone && pendingSubtitleId == null) applyPreferredSubtitles(tracks)
            val wanted = pendingSubtitleId ?: return
            val group = tracks.groups.firstOrNull { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).id?.contains(wanted) == true }
            } ?: return
            pendingSubtitleId = null
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        }
    }

    /**
     * Media3 often leaves embedded subtitles off even with a preferred language (e.g. "es" vs an
     * "es-419" track). Pick one ourselves once per file: full subtitles over forced ones and, for
     * Spanish, Latin American over Castilian.
     */
    private fun applyPreferredSubtitles(tracks: Tracks) {
        val lang = subtitleLanguage
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (lang.isEmpty() || textGroups.isEmpty()) return
        autoSubtitlesDone = true
        val best = textGroups.flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map { g to it } }
            .filter { (g, i) -> Languages.matches(g.getTrackFormat(i).language, lang) }
            .minByOrNull { (g, i) ->
                val f = g.getTrackFormat(i)
                val text = "${f.language} ${f.label}".lowercase()
                val words = text.split(' ', '(', ')', '[', ']', '-')
                var score = 0
                if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || "forced" in text || "forzad" in text) score += 10
                if (lang == "es" && !("419" in text || "latin" in text || "lat" in words)) score += 1
                if ("sdh" in words || "cc" in words) score += 2
                score
            }
        if (best == null) {
            // Nothing in the file in that language (often only English or forced tracks): use the
            // best match from the subtitle addons, unless the user picks something first.
            autoExternalSubtitle(lang)
            return
        }
        if (best.first.isTrackSelected(best.second)) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(best.first.mediaTrackGroup, best.second))
            .build()
    }

    private fun autoExternalSubtitle(lang: String) {
        val request = _status.value.request ?: return
        lifecycleScope.launch {
            val pick = fetchExternalSubtitles().firstOrNull { Languages.matches(it.lang, lang) } ?: return@launch
            if (!userPickedText && _status.value.request == request) selectExternalSubtitle(pick, auto = true)
        }
    }

    /** Per-title choice first, then the global setting. */
    private fun applyLanguagePreferences(request: PlaybackRequest) {
        val settings = graph.settings.current
        val pref = graph.progress.trackPref(request.type, request.tmdbId) ?: TrackPref()
        val audio = pref.audio ?: settings.audioLanguage
        subtitleLanguage = pref.subtitle ?: settings.subtitleLanguage
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(audio.ifEmpty { null })
            .setPreferredTextLanguage(subtitleLanguage.ifEmpty { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage.isEmpty())
            .build()
    }

    fun rememberAudio(language: String?) {
        val request = _status.value.request ?: return
        val iso = Languages.toIso2(language) ?: return
        graph.progress.updateTrackPref(request.type, request.tmdbId) { it.copy(audio = iso) }
    }

    fun rememberSubtitle(language: String?) {
        val request = _status.value.request ?: return
        val value = if (language == null) "" else Languages.toIso2(language) ?: return
        userPickedText = true
        subtitleLanguage = value
        graph.progress.updateTrackPref(request.type, request.tmdbId) { it.copy(subtitle = value) }
    }

    private fun load(intent: Intent?) {
        val raw = intent?.getStringExtra(EXTRA) ?: return finish()
        val request = runCatching { graph.json.decodeFromString(PlaybackRequest.serializer(), raw) }.getOrNull() ?: return finish()
        start(request)
    }

    private fun start(request: PlaybackRequest) {
        loadJob?.cancel()
        statsJob?.cancel()
        prefetchJob?.cancel()
        val previousTorrent = torrentId
        torrentId = null
        pendingSubtitleId = null
        autoSubtitlesDone = false
        userPickedText = false
        subsJob?.cancel()
        subsJob = null
        _status.value = PlayerStatus(
            request = request,
            preparing = if (request.stream.infoHash != null && request.stream.url == null) "Conectando con el torrent…" else "Cargando…",
            pip = _status.value.pip,
            hasNext = request.type == MediaType.Tv,
        )
        player.stop()
        player.clearMediaItems()
        applyLanguagePreferences(request)
        loadJob = lifecycleScope.launch {
            if (previousTorrent != null && previousTorrent != preparedTorrent?.second?.torrentId) {
                graph.torrents.close(previousTorrent)
            }
            val uri = runCatching { resolve(request) }.getOrElse { e ->
                _status.update { it.copy(error = e.message ?: "No se pudo abrir la fuente", preparing = null) }
                return@launch
            }
            httpFactory.setDefaultRequestProperties(request.stream.headers)
            val item = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(request.progressKey)
                .apply { guessMime(request.stream.filename ?: uri)?.let(::setMimeType) }
                .build()
            player.setMediaItem(item, request.startPositionMs)
            player.prepare()
            player.play()
            startProgressLoop()
        }
    }

    private suspend fun resolve(request: PlaybackRequest): String {
        val stream = request.stream
        stream.url?.let { return it }
        val hash = stream.infoHash ?: error("La fuente no tiene enlace reproducible")
        val torrent = preparedTorrent?.takeIf { it.first == hash }?.second ?: graph.torrents.open(stream)
        preparedTorrent = null
        torrentId = torrent.torrentId
        statsJob = lifecycleScope.launch {
            graph.torrents.stats(torrent.torrentId).collect { stats -> _status.update { it.copy(torrent = stats) } }
        }
        return torrent.url
    }

    private fun guessMime(name: String): String? = when (name.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mkv" -> MimeTypes.VIDEO_MATROSKA
        "mp4", "m4v" -> MimeTypes.VIDEO_MP4
        "webm" -> MimeTypes.VIDEO_WEBM
        "m3u8" -> MimeTypes.APPLICATION_M3U8
        "mpd" -> MimeTypes.APPLICATION_MPD
        "ts" -> MimeTypes.VIDEO_MP2T
        else -> null
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveProgress(flush = false)
                maybePrefetchNext()
            }
        }
    }

    /** Near the end of an episode, find the next one's source and start its torrent, so "next" is instant. */
    private fun maybePrefetchNext() {
        val s = _status.value
        val request = s.request ?: return
        if (!s.hasNext || s.nextUp != null || prefetchJob?.isActive == true) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0 || duration - player.currentPosition > PREFETCH_BEFORE_END_MS) return
        prefetchJob = lifecycleScope.launch {
            val next = runCatching { findNext(request) }.getOrNull()
            if (next == null) {
                _status.update { if (it.request == request) it.copy(hasNext = false) else it }
                return@launch
            }
            _status.update { if (it.request == request) it.copy(nextUp = next) else it }
            val hash = next.stream.infoHash
            if (next.stream.url == null && hash != null) {
                runCatching { graph.torrents.open(next.stream) }.onSuccess { preparedTorrent = hash to it }
            }
        }
    }

    fun saveProgress(ended: Boolean = false, flush: Boolean = true) {
        if (!::player.isInitialized) return
        val request = _status.value.request ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (ended) duration else player.currentPosition
        graph.progress.save(
            ProgressEntry(
                key = request.progressKey,
                type = request.type,
                tmdbId = request.tmdbId,
                season = request.season,
                episode = request.episode,
                title = request.title,
                episodeTitle = request.episodeTitle,
                poster = request.poster,
                backdrop = request.backdrop,
                still = request.still,
                imdbId = request.imdbId,
                positionMs = position,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
                lastStream = request.stream,
            ),
        )
        if (flush) graph.progress.flush()
    }

    fun retry() {
        val request = _status.value.request ?: return
        start(request.copy(startPositionMs = player.currentPosition.takeIf { it > 0 } ?: request.startPositionMs))
    }

    private var subsJob: Deferred<List<StremioSubtitle>>? = null

    /** Subtitles from the addons for the current title, fetched once per file. */
    private suspend fun fetchExternalSubtitles(): List<StremioSubtitle> {
        _status.value.externalSubs?.let { return it }
        val request = _status.value.request ?: return emptyList()
        val id = request.stremioId
        if (id == null) {
            _status.update { it.copy(externalSubs = emptyList()) }
            return emptyList()
        }
        val job = subsJob ?: lifecycleScope.async {
            _status.update { it.copy(loadingSubs = true) }
            val subs = runCatching { graph.addons.subtitles(request.stremioType, id) }.getOrDefault(emptyList())
            val preferred = subtitleLanguage
            val sorted = subs.distinctBy { it.url }.sortedBy { if (Languages.matches(it.lang, preferred)) 0 else 1 }
            _status.update { if (it.request == request) it.copy(externalSubs = sorted, loadingSubs = false) else it }
            sorted
        }.also { subsJob = it }
        return job.await()
    }

    fun loadExternalSubtitles() {
        lifecycleScope.launch { fetchExternalSubtitles() }
    }

    fun selectExternalSubtitle(sub: StremioSubtitle, auto: Boolean = false) {
        val current = player.currentMediaItem ?: return
        val id = "ext-" + (sub.id ?: sub.url.hashCode().toString())
        val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setId(id)
            .setMimeType(
                when (sub.url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
                    "vtt" -> MimeTypes.TEXT_VTT
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                },
            )
            .setLanguage(Languages.toIso2(sub.lang))
            .setLabel(Languages.label(sub.lang) + " · internet")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty().filterNot { it.id == id }
        val position = player.currentPosition
        pendingSubtitleId = id
        if (!auto) rememberSubtitle(sub.lang)
        player.setMediaItem(current.buildUpon().setSubtitleConfigurations(existing + config).build(), position)
        player.prepare()
        player.play()
    }

    fun playNext() {
        val s = _status.value
        val request = s.request ?: return
        s.nextUp?.let {
            saveProgress(ended = player.duration > 0 && player.currentPosition > player.duration * 0.9)
            start(it)
            return
        }
        if (advancing) return
        advancing = true
        saveProgress(ended = player.duration > 0 && player.currentPosition > player.duration * 0.9)
        _status.update { it.copy(preparing = "Buscando el siguiente episodio…", error = null) }
        player.pause()
        lifecycleScope.launch {
            val next = runCatching { findNext(request) }.getOrNull()
            advancing = false
            if (next == null) {
                val ended = player.playbackState == Player.STATE_ENDED
                _status.update { it.copy(preparing = null, hasNext = false, error = if (ended) "No hay más episodios disponibles." else null) }
                if (!ended) player.play()
            } else {
                start(next)
            }
        }
    }

    private suspend fun findNext(request: PlaybackRequest): PlaybackRequest? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null
        val imdb = request.imdbId ?: return null
        val tmdb = graph.tmdb
        var next = tmdb.season(request.tmdbId, season).firstOrNull { it.number == episode + 1 }
        if (next == null) {
            val nextSeason = tmdb.details(MediaType.Tv, request.tmdbId).seasons.firstOrNull { it.number > season } ?: return null
            next = tmdb.season(request.tmdbId, nextSeason.number).firstOrNull() ?: return null
        }
        if (!next.released) return null
        val found = mutableListOf<StreamOption>()
        withTimeoutOrNull(25_000) {
            graph.addons.streams(MediaType.Tv.stremio, "$imdb:${next.season}:${next.number}").collect { update ->
                if (update is AddonStreams.Loaded) found += update.streams
            }
        }
        val playable = found.filter { it.kind == StreamKind.Direct || (it.kind == StreamKind.Torrent && graph.torrents.available) }
        val previous = request.stream
        val pick = playable.firstOrNull { previous.bingeGroup != null && it.saved.bingeGroup == previous.bingeGroup }
            ?: playable.firstOrNull { it.addonName == previous.addonName && it.saved.label == previous.label }
            ?: playable.firstOrNull { it.addonName == previous.addonName }
            ?: playable.firstOrNull()
            ?: return null
        val key = ProgressEntry.key(MediaType.Tv, request.tmdbId, next.season, next.number)
        val resume = graph.progress.get(key)?.takeIf { !it.finished }?.positionMs ?: 0L
        return request.copy(
            season = next.season,
            episode = next.number,
            episodeTitle = next.name,
            still = next.stillPath,
            stream = pick.saved,
            startPositionMs = resume,
        )
    }

    // --- Picture in picture -------------------------------------------------------------------

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            Rational((r.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000)
        } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(player.isPlaying)
                setSeamlessResizeEnabled(true)
            }
        }.build()
    }

    private fun updatePipParams() {
        pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    fun enterPip() {
        pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _status.update { it.copy(pip = isInPictureInPictureMode) }
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    // --- Lifecycle ----------------------------------------------------------------------------

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        saveProgress()
        val ids = listOfNotNull(torrentId, preparedTorrent?.second?.torrentId)
        graph.scope.launch { ids.forEach { graph.torrents.close(it) } }
        player.removeListener(listener)
        player.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
