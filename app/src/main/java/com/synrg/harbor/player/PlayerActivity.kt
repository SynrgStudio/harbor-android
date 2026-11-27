package com.synrg.harbor.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.synrg.harbor.R
import com.synrg.harbor.cast.CastDevice
import com.synrg.harbor.cast.CastManager
import com.synrg.harbor.cast.CastMedia
import com.synrg.harbor.cast.CastService
import com.synrg.harbor.cast.CastState
import com.synrg.harbor.cast.LanServer
import com.synrg.harbor.data.Img
import com.synrg.harbor.core.Connection
import com.synrg.harbor.core.Strings
import com.synrg.harbor.data.IntroSkip
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.Settings
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.SubtitleChoice
import com.synrg.harbor.data.SubtitleShift
import com.synrg.harbor.data.TrackPref
import com.synrg.harbor.graph
import com.synrg.harbor.stremio.AddonStreams
import com.synrg.harbor.stremio.StreamKind
import com.synrg.harbor.stremio.StreamOption
import com.synrg.harbor.stremio.StremioSubtitle
import com.synrg.harbor.torrent.TorrentStats
import com.synrg.harbor.torrent.TorrentStream
import com.synrg.harbor.ui.theme.HarborTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
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
    /** Waiting for "Play anyway" before streaming on this metered connection. */
    val dataWarning: Connection? = null,
    val subtitleDelayMs: Long = 0,
    val sleep: SleepTimer? = null,
    /** The timer was set to stop after this video: no next-episode countdown. */
    val stoppedByTimer: Boolean = false,
    /** This series' intro, learned from earlier episodes: "Skip intro" jumps its length. */
    val intro: IntroSkip? = null,
    val introUsed: Boolean = false,
    /** The second subtitle on screen (drawn by the app, at the top). */
    val second: StremioSubtitle? = null,
)

@Immutable
sealed interface SleepTimer {
    /** Pauses at [endsAt] ([SystemClock.elapsedRealtime]). */
    data class At(val endsAt: Long, val minutes: Int) : SleepTimer

    data object EndOfVideo : SleepTimer
}

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
    private var savedSubtitle: SubtitleChoice? = null
    private var advancing = false
    private val subtitleTiming = SubtitleTiming()
    /** "Play anyway" on mobile data counts for the rest of this player session. */
    private var dataApproved = false
    private var dataDecision: CompletableDeferred<Boolean>? = null
    private var sleepJob: Job? = null
    private val sound = SoundEffects()
    private val introLearner = IntroLearner { learned -> saveIntro(learned) }
    private var introJob: Job? = null
    private var secondJob: Job? = null
    private var smartHandled: String? = null
    /** This player sent its video to a TV (the phone's player is paused meanwhile). */
    private var castingHere = false
    private val _secondCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    /** The second subtitle's lines, empty when it is off or still loading. */
    val secondCues: StateFlow<List<SubtitleCue>> = _secondCues

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
        val renderers = HarborRenderersFactory(this, subtitleTiming)
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
            .apply {
                addListener(listener)
                // An explicit session, so the sound effects have something to attach to from the start.
                getSystemService(AudioManager::class.java)?.generateAudioSessionId()?.let { audioSessionId = it }
                sound.apply(audioSessionId, graph.settings.current)
            }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) _status.update { it.copy(preparing = null, ended = false) }
            updateKeepScreenOn()
            if (state == Player.STATE_ENDED) {
                saveProgress(ended = true)
                if (_status.value.sleep == SleepTimer.EndOfVideo) {
                    sleepJob?.cancel()
                    _status.update { it.copy(sleep = null, stoppedByTimer = true) }
                }
                // The UI shows the next-episode countdown; with nothing next, just stop here.
                _status.update { it.copy(ended = true) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateKeepScreenOn()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
            updatePipParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Strings.get(R.string.player_error_connection_lost)
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Strings.get(R.string.player_error_stream_gone)
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> Strings.get(R.string.player_error_cant_decode)
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> Strings.get(R.string.player_error_unsupported_format)
                else -> error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
            }
            _status.update { it.copy(error = message, preparing = null) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

        override fun onAudioSessionIdChanged(audioSessionId: Int) = sound.apply(audioSessionId, graph.settings.current)

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK || _status.value.request?.type != MediaType.Tv) return
            introLearner.onSeek(oldPosition.positionMs, newPosition.positionMs, SystemClock.elapsedRealtime())
            introJob?.cancel()
            introJob = lifecycleScope.launch {
                delay(IntroLearner.MERGE_MS + 500)
                introLearner.finish()
            }
        }

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
        // Wait for the file's tracks (video or audio); a file may have no subtitle track at all.
        if (tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }) return
        autoSubtitlesDone = true
        val embedded = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map { g to it } }
            .filterNot { (g, i) -> g.getTrackFormat(i).id?.contains("ext-") == true }
        if (restoreSubtitle(embedded)) return
        val lang = subtitleLanguage
        if (lang.isEmpty()) return
        val best = embedded
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

    /**
     * Puts back the subtitle last used on this title. An internet one goes straight to its URL; a
     * file track is matched by language and label (so the same release's next episode works too),
     * then by position, only within the same file. True if it handled the choice.
     */
    private fun restoreSubtitle(embedded: List<Pair<Tracks.Group, Int>>): Boolean {
        val saved = savedSubtitle ?: return false
        val video = _status.value.request?.progressKey ?: return false
        val externalUrl = saved.externalUrl
        if (externalUrl != null) {
            if (saved.video != video) return false // another episode has other subtitle files
            // A download carries its subtitles: use the saved file, it works offline.
            val local = graph.downloads.get(video)?.subtitles?.firstOrNull { it.source == externalUrl }
                ?.takeIf { _status.value.request?.isLocal == true }
            selectExternalSubtitle(StremioSubtitle(saved.externalId, local?.url ?: externalUrl, saved.language), auto = true)
            return true
        }
        val match = embedded.firstOrNull { (g, i) ->
            val f = g.getTrackFormat(i)
            f.language == saved.language && f.label == saved.label
        } ?: embedded.getOrNull(saved.index ?: -1)?.takeIf { saved.video == video }
            ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
            .build()
        return true
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
        savedSubtitle = pref.subtitleChoice.takeIf { subtitleLanguage.isNotEmpty() }
        applySubtitleDelay(pref.subtitleDelay?.takeIf { it.video == request.progressKey }?.ms ?: 0L)
        _status.update { it.copy(intro = pref.intro.takeIf { request.type == MediaType.Tv }, introUsed = false) }
        pref.secondSubtitle?.let { autoSecondSubtitle(request, it) }
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

    /** Subtitles off for this title. */
    fun rememberSubtitlesOff() {
        val request = _status.value.request ?: return
        userPickedText = true
        subtitleLanguage = ""
        savedSubtitle = null
        applySubtitleDelay(0)
        graph.progress.updateTrackPref(request.type, request.tmdbId) { it.copy(subtitle = "", subtitleChoice = null, subtitleDelay = null) }
    }

    private fun applySubtitleDelay(ms: Long) {
        subtitleTiming.delayUs = ms * 1000
        _status.update { it.copy(subtitleDelayMs = ms) }
    }

    /** Subtitle sync by hand: positive shows them later. Kept for this video. */
    fun setSubtitleDelay(ms: Long) {
        val request = _status.value.request ?: return
        val value = ms.coerceIn(-60_000L, 60_000L)
        applySubtitleDelay(value)
        graph.progress.updateTrackPref(request.type, request.tmdbId) {
            it.copy(subtitleDelay = if (value == 0L) null else SubtitleShift(request.progressKey, value))
        }
    }

    /** A track of the file picked in the panel; [position] is its place among the file's subtitles. */
    fun rememberEmbeddedSubtitle(format: Format, position: Int) {
        val request = _status.value.request ?: return
        userPickedText = true
        val choice = SubtitleChoice(request.progressKey, language = format.language, label = format.label, index = position)
        rememberChoice(request, choice, Languages.toIso2(format.language))
    }

    private fun rememberExternalSubtitle(sub: StremioSubtitle, picked: Boolean) {
        val request = _status.value.request ?: return
        if (picked) userPickedText = true
        // A downloaded subtitle is remembered by where it came from, so streaming finds it again.
        val choice = SubtitleChoice(request.progressKey, externalUrl = graph.downloads.subtitleSource(sub.url), externalId = sub.id, language = sub.lang)
        // An automatic pick keeps the title's language as it was; it only pins which file it was.
        rememberChoice(request, choice, if (picked) Languages.toIso2(sub.lang) else null)
    }

    private fun rememberChoice(request: PlaybackRequest, choice: SubtitleChoice, language: String?) {
        val previous = savedSubtitle
        // Another subtitle needs its own sync; restoring the same one keeps it.
        val sameSubtitle = previous != null && previous.externalUrl == choice.externalUrl &&
            previous.language == choice.language && previous.label == choice.label
        savedSubtitle = choice
        if (language != null) subtitleLanguage = language
        if (!sameSubtitle && _status.value.subtitleDelayMs != 0L) applySubtitleDelay(0)
        graph.progress.updateTrackPref(request.type, request.tmdbId) { pref ->
            pref.copy(
                subtitle = language ?: pref.subtitle,
                subtitleChoice = choice,
                subtitleDelay = if (sameSubtitle) pref.subtitleDelay else null,
            )
        }
    }

    // --- Play on a TV -----------------------------------------------------------------------------

    val castState: StateFlow<CastState?> get() = graph.cast.state
    val castDevices: StateFlow<List<CastDevice>> get() = graph.cast.devices
    val castSearching: StateFlow<Boolean> get() = graph.cast.searching

    /** The picker is open: look for Chromecasts and TVs. */
    fun openCastPicker() = graph.cast.discover()

    fun closeCastPicker() = graph.cast.stopDiscovery()

    /** Sends what is playing to [device], from the same minute and with the same subtitle. */
    fun castTo(device: CastDevice) {
        val request = _status.value.request ?: return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val position = player.currentPosition
        player.pause()
        castingHere = true
        lifecycleScope.launch {
            val subtitle = runCatching { subtitleForTv() }.getOrNull()
            val local = CastManager.localFile(uri)
            val headers = request.stream.headers
            val mime = CastManager.mimeFor(request.stream.filename ?: uri)
            val source = if (local != null) LanServer.Item.LocalFile(local, mime) else LanServer.Item.Upstream(uri, headers, mime)
            // The torrent engine only listens on the phone itself; links with headers need the phone too.
            val direct = uri.takeIf { local == null && headers.isEmpty() && !uri.startsWith("http://127.0.0.1") && !uri.startsWith("http://localhost") }
            val media = CastMedia(
                directUrl = direct, source = source, mime = mime,
                title = request.title, subtitle = request.displaySubtitle,
                image = Img.backdrop(request.backdrop, "w1280") ?: Img.poster(request.poster, "w780"),
                startMs = position, subtitles = subtitle?.first, subtitleLanguage = subtitle?.second,
            )
            CastService.start(this@PlayerActivity)
            graph.cast.start(device, media)
        }
    }

    fun castTogglePause() = graph.cast.togglePause()

    fun castSeek(ms: Long) = graph.cast.seek(ms)

    /** Back to the phone, from where the TV was. */
    fun stopCasting() {
        castingHere = false
        lifecycleScope.launch {
            val position = graph.cast.stop()
            if (position > 0) player.seekTo(position)
            player.play()
        }
    }

    /** The internet or downloaded subtitle on screen, read and shifted by the sync set by hand (a file's own track can't be sent). */
    private suspend fun subtitleForTv(): Pair<List<SubtitleCue>, String?>? {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
        val selectedId = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).id } } ?: return null
        val config = player.currentMediaItem?.localConfiguration?.subtitleConfigurations
            ?.firstOrNull { cfg -> cfg.id?.let { selectedId.contains(it) } == true } ?: return null
        val shift = _status.value.subtitleDelayMs
        val cues = withContext(Dispatchers.IO) { readCues(config.uri.toString()) }
            .map { it.copy(startMs = (it.startMs + shift).coerceAtLeast(0), endMs = (it.endMs + shift).coerceAtLeast(0)) }
        return cues to config.language
    }

    // --- Sound -----------------------------------------------------------------------------------

    /** Night mode, clearer dialogue and boost: saved for every video, applied right away. */
    fun updateSound(transform: (Settings) -> Settings) {
        graph.settings.update(transform)
        sound.apply(player.audioSessionId, graph.settings.current)
    }

    // --- Skip intro ------------------------------------------------------------------------------

    private fun saveIntro(intro: IntroSkip) {
        val request = _status.value.request?.takeIf { it.type == MediaType.Tv } ?: return
        graph.progress.updateTrackPref(request.type, request.tmdbId) { it.copy(intro = intro) }
        _status.update { if (it.request == request) it.copy(intro = intro) else it }
    }

    fun skipIntro() {
        val intro = _status.value.intro ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + intro.lengthMs).coerceAtMost(duration - 1_000))
        _status.update { it.copy(introUsed = true) }
    }

    // --- Second subtitle -------------------------------------------------------------------------

    /** Turns the second subtitle on with [sub] (or off), and remembers its language for this title. */
    fun selectSecondSubtitle(sub: StremioSubtitle?) {
        val request = _status.value.request ?: return
        graph.progress.updateTrackPref(request.type, request.tmdbId) {
            it.copy(secondSubtitle = sub?.let { s -> Languages.toIso2(s.lang) ?: s.lang })
        }
        showSecond(sub)
    }

    private fun showSecond(sub: StremioSubtitle?) {
        secondJob?.cancel()
        _secondCues.value = emptyList()
        _status.update { it.copy(second = sub) }
        if (sub == null) return
        secondJob = lifecycleScope.launch {
            // One more try: subtitle servers drop a request now and then.
            val cues = withContext(Dispatchers.IO) {
                runCatching { readCues(sub.url) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: runCatching { readCues(sub.url) }.getOrDefault(emptyList())
            }
            if (_status.value.second != sub) return@launch
            if (cues.isEmpty()) {
                _status.update { it.copy(second = null) }
                android.widget.Toast.makeText(this@PlayerActivity, R.string.subs_second_failed, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                _secondCues.value = cues
            }
        }
    }

    /** The title's second language from last time: its first file that isn't the main subtitle. */
    private fun autoSecondSubtitle(request: PlaybackRequest, language: String) {
        lifecycleScope.launch {
            val subs = fetchExternalSubtitles()
            if (_status.value.request != request || _status.value.second != null) return@launch
            val main = savedSubtitle?.externalUrl
            subs.firstOrNull { Languages.matches(it.lang, language) && graph.downloads.subtitleSource(it.url) != main }?.let(::showSecond)
        }
    }

    private fun readCues(url: String): List<SubtitleCue> {
        val bytes = if (url.startsWith("file:")) {
            File(Uri.parse(url).path ?: return emptyList()).readBytes()
        } else {
            graph.mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { it.body.bytes() }
        }
        return SubtitleFile.parse(SubtitleFile.decode(bytes))
    }

    private fun load(intent: Intent?) {
        val raw = intent?.getStringExtra(EXTRA) ?: return finish()
        val request = runCatching { graph.json.decodeFromString(PlaybackRequest.serializer(), raw) }.getOrNull() ?: return finish()
        start(request)
    }

    private fun start(request: PlaybackRequest) {
        // A skip in progress belongs to the video that is ending.
        introJob?.cancel()
        introLearner.finish()
        secondJob?.cancel()
        _secondCues.value = emptyList()
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
        val preparing = if (request.stream.infoHash != null && request.stream.url == null) Strings.get(R.string.player_connecting_torrent) else Strings.get(R.string.player_loading)
        _status.value = PlayerStatus(
            request = request,
            preparing = preparing,
            pip = _status.value.pip,
            hasNext = request.type == MediaType.Tv,
            sleep = _status.value.sleep,
        )
        player.stop()
        player.clearMediaItems()
        applyLanguagePreferences(request)
        loadJob = lifecycleScope.launch {
            if (previousTorrent != null && previousTorrent != preparedTorrent?.second?.torrentId) {
                graph.torrents.close(previousTorrent)
            }
            val connection = graph.network.current
            if (!dataApproved && connection.metered && graph.settings.current.askOnMobileData && !request.isLocal) {
                val decision = CompletableDeferred<Boolean>().also { dataDecision = it }
                _status.update { it.copy(dataWarning = connection, preparing = null) }
                if (!decision.await()) {
                    finish()
                    return@launch
                }
                _status.update { it.copy(dataWarning = null, preparing = preparing) }
            }
            val uri = runCatching { resolve(request) }.getOrElse { e ->
                _status.update { it.copy(error = e.message ?: Strings.get(R.string.player_error_cant_open), preparing = null) }
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

    /** The answer to the mobile-data warning: play anyway, or leave the player. */
    fun answerDataWarning(play: Boolean) {
        if (play) dataApproved = true
        dataDecision?.complete(play)
        dataDecision = null
    }

    // --- Sleep timer ----------------------------------------------------------------------------

    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        sleepJob = null
        _status.update { it.copy(sleep = timer) }
        if (timer is SleepTimer.At) {
            sleepJob = lifecycleScope.launch {
                delay(timer.endsAt - SystemClock.elapsedRealtime())
                fallAsleep()
            }
        }
    }

    /** Lowers the volume over a few seconds, then pauses: no sudden silence if still awake. */
    private suspend fun fallAsleep() {
        val volume = player.volume
        try {
            if (player.isPlaying) {
                for (i in 1..16) {
                    player.volume = volume * (1f - i / 16f)
                    delay(500)
                }
            }
            player.pause()
        } finally {
            player.volume = volume
            _status.update { it.copy(sleep = null) }
        }
    }

    /** The screen stays on while playing or loading; paused, it sleeps as usual. */
    private fun updateKeepScreenOn() {
        val on = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private suspend fun resolve(request: PlaybackRequest): String {
        val stream = request.stream
        stream.url?.let { return it }
        val hash = stream.infoHash ?: error(Strings.get(R.string.player_error_no_link))
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
                if (player.isPlaying || (castingHere && graph.cast.state.value?.playing == true)) {
                    saveProgress(flush = false)
                    _status.value.request?.let { r -> graph.watchLog.add(r.type, r.tmdbId, r.title, r.poster, 5) }
                }
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
        // On a TV: its position is the one that counts.
        val tv = graph.cast.state.value?.takeIf { castingHere && it.durationMs > 0 }
        val duration = tv?.durationMs ?: player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (ended) duration else tv?.positionMs ?: player.currentPosition
        val entry = ProgressEntry(
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
            )
        graph.progress.save(entry)
        // Smart downloads: once per video, when it counts as watched.
        if (entry.finished && smartHandled != request.progressKey) {
            smartHandled = request.progressKey
            graph.autoDownloads.episodeFinished(request)
        }
        if (flush) {
            graph.progress.flush()
            graph.sync.progressChanged(entry) // pauses, exits and endings reach the Stremio account
        }
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
        // Subtitles saved with a download come first: they work without internet.
        val local = if (request.isLocal) {
            graph.downloads.get(request.progressKey)?.subtitles.orEmpty().mapIndexed { i, s -> StremioSubtitle("local$i", s.url, s.lang) }
        } else emptyList()
        if (id == null) {
            _status.update { it.copy(externalSubs = local) }
            return local
        }
        val job = subsJob ?: lifecycleScope.async {
            _status.update { it.copy(loadingSubs = true) }
            val online = runCatching { graph.addons.subtitles(request.stremioType, id) }.getOrDefault(emptyList())
            val sources = local.map { graph.downloads.subtitleSource(it.url) }.toSet()
            val subs = local + online.filterNot { it.url in sources }
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
        rememberExternalSubtitle(sub, picked = !auto)
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
        _status.update { it.copy(preparing = Strings.get(R.string.player_finding_next), error = null) }
        player.pause()
        lifecycleScope.launch {
            val next = runCatching { findNext(request) }.getOrNull()
            advancing = false
            if (next == null) {
                val ended = player.playbackState == Player.STATE_ENDED
                _status.update { it.copy(preparing = null, hasNext = false, error = if (ended) Strings.get(R.string.player_error_no_more_episodes) else null) }
                if (!ended) player.play()
            } else {
                start(next)
            }
        }
    }

    private suspend fun findNext(request: PlaybackRequest): PlaybackRequest? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null
        // Downloaded next episode first: it plays without internet (and without looking anything up).
        graph.downloads.list.firstOrNull { d ->
            d.type == MediaType.Tv && d.tmdbId == request.tmdbId && d.localFile != null &&
                ((d.season == season && d.episode == episode + 1) || (d.season == season + 1 && d.episode == 1))
        }?.let { d ->
            val resume = graph.progress.get(d.id)?.takeIf { !it.finished }?.positionMs ?: 0L
            return d.playback(resume)
        }
        val imdb = request.imdbId ?: return null
        val meta = graph.meta
        var next = meta.season(request.tmdbId, season).firstOrNull { it.number == episode + 1 }
        if (next == null) {
            val nextSeason = meta.details(MediaType.Tv, request.tmdbId).seasons.firstOrNull { it.number > season } ?: return null
            next = meta.season(request.tmdbId, nextSeason.number).firstOrNull() ?: return null
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
        if (castingHere) graph.scope.launch { graph.cast.stop() }
        val ids = listOfNotNull(torrentId, preparedTorrent?.second?.torrentId)
        graph.scope.launch { ids.forEach { graph.torrents.close(it) } }
        player.removeListener(listener)
        sound.release()
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
