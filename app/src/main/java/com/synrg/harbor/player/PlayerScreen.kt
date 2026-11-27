package com.synrg.harbor.player

import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.synrg.harbor.R
import com.synrg.harbor.core.Connection
import com.synrg.harbor.data.Img
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.GlassButton
import com.synrg.harbor.ui.components.PrimaryButton
import com.synrg.harbor.ui.components.RoundIconButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Mirrors the bits of player state the UI needs, updated by a listener instead of polling. */
@Stable
private class UiPlayerState(private val player: Player) : Player.Listener {
    var isPlaying by mutableStateOf(player.isPlaying)
    var playbackState by mutableIntStateOf(player.playbackState)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)

    override fun onEvents(player: Player, events: Player.Events) {
        isPlaying = player.isPlaying
        playbackState = player.playbackState
        duration = player.duration.coerceAtLeast(0L)
        tick()
    }

    fun tick() {
        position = player.currentPosition.coerceAtLeast(0L)
        buffered = player.bufferedPosition.coerceAtLeast(0L)
    }
}

private enum class PlayerPanel { Tracks, Sleep, Cast }

/** What a vertical/horizontal swipe is adjusting, shown in the centre while dragging. */
@Immutable
private sealed interface Hud {
    data class Level(val icon: ImageVector, @StringRes val label: Int, val fraction: Float) : Hud
    data class Seek(val targetMs: Long, val deltaMs: Long) : Hud
}

@Composable
fun PlayerScreen(player: ExoPlayer, controller: PlayerActivity) {
    val context = LocalContext.current
    val status by controller.status.collectAsStateWithLifecycle()
    val ui = remember(player) { UiPlayerState(player) }
    DisposableEffect(player) {
        player.addListener(ui)
        onDispose { player.removeListener(ui) }
    }
    val settings = remember { context.graph.settings.current }
    val secondCues by controller.secondCues.collectAsStateWithLifecycle()
    val cast by controller.castState.collectAsStateWithLifecycle()
    val audio = remember { context.getSystemService(AudioManager::class.java) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var scrubbing by remember { mutableStateOf(false) }
    var seekFlash by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    // Kept while the panel slides out, so its content does not vanish mid-animation.
    var lastPanel by remember { mutableStateOf(PlayerPanel.Tracks) }
    val showTracks = panel != null
    // The lock ignores touches (in a pocket, in bed); a tap only shows how to unlock.
    var locked by rememberSaveable { mutableStateOf(false) }
    var lockHint by remember { mutableStateOf(false) }
    val lockActive = locked && status.error == null && status.dataWarning == null
    BackHandler(enabled = lockActive) { lockHint = true }
    LaunchedEffect(lockHint) {
        if (lockHint) {
            delay(2_500)
            lockHint = false
        }
    }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var nextDismissed by remember(status.request) { mutableStateOf(false) }
    var countdown by remember(status.request) { mutableIntStateOf(0) }

    // Position updates only while someone can see them (controls, PiP, or the next-episode card).
    val nearEnd = status.hasNext && ui.duration > 0 && ui.duration - ui.position < 32_000
    val watchIntro = status.intro != null && !status.introUsed
    LaunchedEffect(controlsVisible, status.pip, nearEnd, status.nextUp, watchIntro) {
        while (controlsVisible || status.pip || status.nextUp != null || watchIntro) {
            ui.tick()
            delay(if (controlsVisible) 250 else 1_000)
        }
    }
    // Auto-hide.
    LaunchedEffect(controlsVisible, ui.isPlaying, interaction, scrubbing, showTracks) {
        if (controlsVisible && ui.isPlaying && !scrubbing && !showTracks) {
            delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(650)
            seekFlash = null
        }
    }
    // End of an episode: 5 s countdown when the next one is ready, otherwise go look for it.
    LaunchedEffect(status.ended, status.nextUp, nextDismissed) {
        if (!status.ended || !status.hasNext || nextDismissed || !settings.autoplayNext || status.stoppedByTimer) return@LaunchedEffect
        if (status.nextUp != null) {
            for (i in 5 downTo 1) {
                countdown = i
                delay(1_000)
            }
        }
        controller.playNext()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                    subtitleView?.let { SubtitleStyle.apply(it, settings) }
                }
            },
            update = { it.resizeMode = resize },
            modifier = Modifier.fillMaxSize(),
        )

        if (status.pip) return@Box

        // On a TV: the phone becomes the remote.
        cast?.let { tv ->
            CastingView(
                tv, status.request,
                onBack = { controller.finish() },
                onToggle = controller::castTogglePause,
                onSeek = controller::castSeek,
                onPhone = controller::stopCasting,
            )
            return@Box
        }

        if (secondCues.isNotEmpty()) {
            SecondSubtitle(
                player, secondCues, status.subtitleDelayMs, settings,
                lowered = controlsVisible && !lockActive,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // Gestures: tap toggles controls, double tap on a side skips 10 s; horizontal swipe seeks,
        // vertical swipe on the left/right half sets brightness/volume. Edges are left to Android.
        if (lockActive) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { lockHint = true } })
        } else Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interaction++
                        },
                        onDoubleTap = { offset ->
                            val forward = offset.x > size.width / 2
                            if (forward) player.seekForward() else player.seekBack()
                            val previous = seekFlash
                            seekFlash = forward to (if (previous?.first == forward) previous.second + 10 else 10)
                            ui.tick()
                            interaction++
                        },
                    )
                }
                .pointerInput(Unit) {
                    var mode = 0
                    var startX = 0f
                    var total = Offset.Zero
                    var startPos = 0L
                    var startLevel = 0f
                    var target = 0L
                    val edge = 36.dp.toPx()
                    val decide = 18.dp.toPx()
                    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectDragGestures(
                        onDragStart = { o ->
                            total = Offset.Zero
                            mode = if (o.y < edge || o.y > size.height - edge || o.x < edge || o.x > size.width - edge) -1 else 0
                            startX = o.x
                        },
                        onDragEnd = {
                            if (mode == 1) {
                                player.seekTo(target)
                                ui.tick()
                            }
                            mode = 0
                            hud = null
                        },
                        onDragCancel = {
                            mode = 0
                            hud = null
                        },
                        onDrag = { change, drag ->
                            if (mode == -1) return@detectDragGestures
                            change.consume()
                            total += drag
                            if (mode == 0 && total.getDistance() > decide) {
                                mode = when {
                                    abs(total.x) > abs(total.y) -> 1
                                    startX < size.width / 2 -> 2
                                    else -> 3
                                }
                                startPos = player.currentPosition
                                startLevel = when (mode) {
                                    2 -> controller.window.attributes.screenBrightness.takeIf { it >= 0 }
                                        ?: (Settings.System.getInt(controller.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                                    3 -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                                    else -> 0f
                                }
                            }
                            when (mode) {
                                1 -> {
                                    val duration = player.duration.coerceAtLeast(1)
                                    // A full-width swipe moves 2 minutes, or a fifth of a short video.
                                    val span = minOf(120_000L, duration / 5).coerceAtLeast(20_000L)
                                    target = (startPos + (total.x / size.width * span).toLong()).coerceIn(0, duration)
                                    hud = Hud.Seek(target, target - startPos)
                                }
                                2 -> {
                                    val level = (startLevel - total.y / (size.height * 0.8f)).coerceIn(0.01f, 1f)
                                    controller.window.attributes = controller.window.attributes.apply { screenBrightness = level }
                                    hud = Hud.Level(HIcons.Brightness, R.string.player_brightness, level)
                                }
                                3 -> {
                                    val level = (startLevel - total.y / (size.height * 0.8f)).coerceIn(0f, 1f)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVolume).roundToInt(), 0)
                                    hud = Hud.Level(HIcons.Volume, R.string.player_volume, level)
                                }
                            }
                        },
                    )
                },
        )

        seekFlash?.let { (forward, seconds) ->
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(0.38f).align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            if (forward) listOf(Color.Transparent, Color(0x33FFFFFF)) else listOf(Color(0x33FFFFFF), Color.Transparent),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (forward) "+$seconds s" else "−$seconds s", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }

        val loading = status.preparing != null || ui.playbackState == Player.STATE_BUFFERING
        if (loading && status.error == null && hud == null) {
            LoadingIndicator(status)
        }

        val intro = status.intro
        val skipIntro = intro != null && !status.introUsed && ui.position in IntroLearner.window(intro) &&
            status.error == null && !lockActive && ui.duration > 0
        val skipIntroLabel = intro?.let { stringResource(R.string.player_skip_intro, formatTime(it.lengthMs)) }

        val next = status.nextUp
        val showNextCard = next != null && (nearEnd || status.ended) && !nextDismissed && status.error == null && !showTracks && !lockActive
        AnimatedVisibility(
            visible = controlsVisible && !lockActive && status.error == null && status.dataWarning == null && hud == null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(220)),
        ) {
            Controls(
                player = player,
                ui = ui,
                status = status,
                showCenter = !loading,
                showNextButton = !showNextCard,
                onBack = { controller.finish() },
                onTracks = {
                    panel = PlayerPanel.Tracks
                    lastPanel = PlayerPanel.Tracks
                    controller.loadExternalSubtitles()
                },
                onSleep = {
                    panel = PlayerPanel.Sleep
                    lastPanel = PlayerPanel.Sleep
                },
                onCast = {
                    panel = PlayerPanel.Cast
                    lastPanel = PlayerPanel.Cast
                },
                onLock = {
                    locked = true
                    controlsVisible = false
                    lockHint = true
                },
                onResize = {
                    resize = if (resize == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    interaction++
                },
                onPip = { controller.enterPip() },
                onNext = { controller.playNext() },
                skipIntroLabel = skipIntroLabel.takeIf { skipIntro },
                onSkipIntro = { controller.skipIntro(); interaction++ },
                onScrub = { scrubbing = it; interaction++ },
                onInteract = { interaction++ },
            )
        }

        hud?.let { HudView(it, Modifier.align(Alignment.Center)) }

        // With the controls hidden, "Skip intro" stands on its own, like the next-episode card.
        AnimatedVisibility(
            visible = skipIntro && !controlsVisible && !showTracks && hud == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 44.dp),
        ) {
            GlassButton(skipIntroLabel.orEmpty(), HIcons.SkipNext, { controller.skipIntro() })
        }

        AnimatedVisibility(
            visible = showNextCard,
            enter = fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 3 },
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 96.dp),
        ) {
            if (next != null) {
                NextEpisodeCard(
                    next = next,
                    countdown = if (status.ended && settings.autoplayNext && !status.stoppedByTimer) countdown else 0,
                    onPlay = { controller.playNext() },
                    onDismiss = { nextDismissed = true },
                )
            }
        }

        status.error?.let { message ->
            ErrorPanel(message, onRetry = controller::retry, onBack = { controller.finish() })
        }

        status.dataWarning?.let { connection ->
            DataWarningPanel(connection, onPlay = { controller.answerDataWarning(true) }, onCancel = { controller.answerDataWarning(false) })
        }

        AnimatedVisibility(
            visible = lockActive && lockHint,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xE6111318))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
                    .clickable {
                        locked = false
                        lockHint = false
                        controlsVisible = true
                        interaction++
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HIcons.LockOpen, null, tint = HarborColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.player_locked_hint), style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }

        AnimatedVisibility(
            visible = showTracks,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures { panel = null } },
            )
        }
        AnimatedVisibility(
            visible = showTracks,
            enter = slideInHorizontally(tween(240)) { it },
            exit = slideOutHorizontally(tween(200)) { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            when (panel ?: lastPanel) {
                PlayerPanel.Tracks -> TracksPanel(player, status, controller, onClose = { panel = null })
                PlayerPanel.Cast -> CastPanel(controller, onPicked = { panel = null }, onClose = { panel = null })
                PlayerPanel.Sleep -> SleepPanel(
                    current = status.sleep,
                    isEpisode = status.request?.episode != null,
                    onPick = {
                        controller.setSleepTimer(it)
                        panel = null
                    },
                    onClose = { panel = null },
                )
            }
        }
    }
}

/** Playing on a TV: what, where, and the controls, with the title's artwork behind. */
@Composable
private fun CastingView(
    tv: com.synrg.harbor.cast.CastState,
    request: PlaybackRequest?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onPhone: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            Img.backdrop(request?.backdrop, "w1280") ?: Img.poster(request?.poster, "w780"), null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.3f,
        )
        RoundIconButton(HIcons.Back, stringResource(R.string.player_exit), onBack, modifier = Modifier.padding(start = 28.dp, top = 18.dp))
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HIcons.CastConnected, null, tint = HarborColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cast_playing_on, tv.device.name), style = MaterialTheme.typography.labelLarge, color = HarborColors.Accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(request?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            request?.displaySubtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(22.dp))
            when {
                tv.failed -> Text(
                    stringResource(R.string.cast_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.Warn,
                    textAlign = TextAlign.Center,
                )
                tv.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = HarborColors.Accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.cast_connecting), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkipButton(forward = false) { onSeek(tv.positionMs - 10_000) }
                        Box(contentAlignment = Alignment.Center) {
                            RoundIconButton(
                                if (tv.playing) HIcons.Pause else HIcons.Play,
                                stringResource(if (tv.playing) R.string.player_pause else R.string.player_play),
                                onToggle, size = 72.dp,
                            )
                            if (tv.loading) CircularProgressIndicator(Modifier.size(80.dp), color = HarborColors.Accent, strokeWidth = 2.dp)
                        }
                        SkipButton(forward = true) { onSeek(tv.positionMs + 10_000) }
                    }
                    Spacer(Modifier.height(18.dp))
                    SeekBar(position = tv.positionMs, buffered = 0, duration = tv.durationMs, onSeek = onSeek, onScrub = {})
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(formatTime(tv.positionMs), style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(if (tv.durationMs > 0) formatTime(tv.durationMs) else "--:--", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            GlassButton(stringResource(R.string.cast_back_to_phone), HIcons.Pip, onPhone)
        }
    }
}

/** The second subtitle, at the top (below the controls while they show), in the user's subtitle style. */
@Composable
private fun SecondSubtitle(player: Player, cues: List<SubtitleCue>, delayMs: Long, settings: com.synrg.harbor.data.Settings, lowered: Boolean, modifier: Modifier) {
    var text by remember(cues) { mutableStateOf<String?>(null) }
    LaunchedEffect(cues, delayMs) {
        while (true) {
            text = SubtitleFile.at(cues, player.currentPosition - delayMs)
            delay(100)
        }
    }
    val top by animateDpAsState(if (lowered) 84.dp else 18.dp, tween(200), label = "second")
    text?.let {
        StyledSubtitle(it, settings, (18 * settings.subtitleScale).sp, modifier.padding(top = top, start = 56.dp, end = 56.dp))
    }
}

@Composable
private fun HudView(hud: Hud, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xB3000000)).padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (hud) {
            is Hud.Level -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(hud.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${stringResource(hud.label)}  ${(hud.fraction * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.width(180.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))
                        .drawBehind { drawRect(HarborColors.Accent, size = size.copy(width = size.width * hud.fraction)) },
                )
            }
            is Hud.Seek -> {
                Text(formatTime(hud.targetMs), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                val sign = if (hud.deltaMs >= 0) "+" else "−"
                Text(sign + formatTime(abs(hud.deltaMs)), style = MaterialTheme.typography.titleSmall, color = HarborColors.Accent)
            }
        }
    }
}

@Composable
private fun NextEpisodeCard(next: PlaybackRequest, countdown: Int, onPlay: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE6111318))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHigh)) {
            AsyncImage(Img.still(next.still) ?: Img.backdrop(next.backdrop, "w780"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(stringResource(R.string.player_next_episode), style = MaterialTheme.typography.labelMedium, color = HarborColors.Accent)
            Text(
                next.displaySubtitle.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    if (countdown > 0) stringResource(R.string.player_watch_now_countdown, countdown) else stringResource(R.string.player_watch_now),
                    HIcons.Play,
                    onPlay,
                )
                Spacer(Modifier.width(8.dp))
                RoundIconButton(HIcons.Close, stringResource(R.string.common_close), onDismiss, size = 40.dp)
            }
        }
    }
}

@Composable
private fun LoadingIndicator(status: PlayerStatus) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(44.dp), color = HarborColors.Accent, strokeWidth = 3.dp)
        val torrent = status.torrent
        val line = when {
            status.preparing != null && status.torrent == null -> status.preparing
            torrent != null && torrent.peers > 0 -> pluralStringResource(R.plurals.player_peers_speed, torrent.peers, torrent.peers, formatSpeed(torrent.downloadBps))
            torrent != null -> stringResource(R.string.player_finding_peers)
            else -> status.preparing
        }
        if (line != null) {
            Spacer(Modifier.height(14.dp))
            Text(line, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

private fun formatSpeed(bps: Long): String =
    if (bps >= 1_048_576) String.format(Locale.ROOT, "%.1f MB/s", bps / 1_048_576.0) else "${bps / 1024} KB/s"

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

@Composable
private fun Controls(
    player: Player,
    ui: UiPlayerState,
    status: PlayerStatus,
    showCenter: Boolean,
    showNextButton: Boolean,
    onBack: () -> Unit,
    onTracks: () -> Unit,
    onSleep: () -> Unit,
    onCast: () -> Unit,
    onLock: () -> Unit,
    onResize: () -> Unit,
    onPip: () -> Unit,
    onNext: () -> Unit,
    skipIntroLabel: String?,
    onSkipIntro: () -> Unit,
    onScrub: (Boolean) -> Unit,
    onInteract: () -> Unit,
) {
    val request = status.request
    Box(Modifier.fillMaxSize()) {
        // Scrims keep text readable over bright scenes.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0xB3000000), 0.25f to Color(0x33000000), 0.7f to Color(0x33000000), 1f to Color(0xCC000000)),
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(HIcons.Back, stringResource(R.string.player_exit), onBack)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(request?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                request?.displaySubtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            status.torrent?.let {
                Text(
                    pluralStringResource(R.plurals.player_peers_speed, it.peers, it.peers, formatSpeed(it.downloadBps)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            sleepLabel(status.sleep)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = HarborColors.Accent, modifier = Modifier.padding(end = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundIconButton(HIcons.Cast, stringResource(R.string.cast_title), onCast)
                RoundIconButton(HIcons.Lock, stringResource(R.string.player_lock_screen), onLock)
                RoundIconButton(HIcons.Bedtime, stringResource(R.string.sleep_timer), onSleep, tint = if (status.sleep != null) HarborColors.Accent else Color.White)
                RoundIconButton(HIcons.Subtitles, stringResource(R.string.player_audio_subtitles), onTracks)
                RoundIconButton(HIcons.AspectRatio, stringResource(R.string.player_resize), onResize)
                RoundIconButton(HIcons.Pip, stringResource(R.string.player_pip), onPip)
            }
        }

        if (showCenter) {
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(forward = false) { player.seekBack(); ui.tick(); onInteract() }
                RoundIconButton(
                    if (ui.isPlaying) HIcons.Pause else HIcons.Play,
                    if (ui.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                    onClick = {
                        if (ui.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                        onInteract()
                    },
                    size = 72.dp,
                )
                SkipButton(forward = true) { player.seekForward(); ui.tick(); onInteract() }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp)) {
            val next = showNextButton && status.hasNext && request?.episode != null
            if (next || skipIntroLabel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    if (skipIntroLabel != null) GlassButton(skipIntroLabel, HIcons.SkipNext, onSkipIntro)
                    if (next) GlassButton(stringResource(R.string.player_next), HIcons.SkipNext, onNext)
                }
                Spacer(Modifier.height(10.dp))
            }
            SeekBar(
                position = ui.position,
                buffered = ui.buffered,
                duration = ui.duration,
                onSeek = { player.seekTo(it); ui.tick() },
                onScrub = onScrub,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(formatTime(ui.position), style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.duration > 0) "−" + formatTime(ui.duration - ui.position) else "--:--",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** "Pausing in 23 min" while a timer runs; the controls recompose often enough to keep it current. */
@Composable
private fun sleepLabel(timer: SleepTimer?): String? = when (timer) {
    null -> null
    SleepTimer.EndOfVideo -> stringResource(R.string.sleep_pausing_at_end)
    is SleepTimer.At -> {
        val left = (timer.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        if (left < 60_000) stringResource(R.string.sleep_pausing_in_seconds, left / 1000)
        else stringResource(R.string.sleep_pausing_in_minutes, (left + 59_999) / 60_000)
    }
}

@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        RoundIconButton(if (forward) HIcons.Forward else HIcons.Replay, if (forward) stringResource(R.string.player_skip_forward) else stringResource(R.string.player_skip_back), onClick, size = 52.dp)
        Text("10", color = Color.White, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SeekBar(position: Long, buffered: Long, duration: Long, onSeek: (Long) -> Unit, onScrub: (Boolean) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: if (duration > 0) position.toFloat() / duration else 0f
    val thickness by animateFloatAsState(if (dragFraction != null) 7f else 4f, tween(120), label = "track")
    Box(Modifier.fillMaxWidth().height(30.dp)) {
        dragFraction?.let { f ->
            Text(
                formatTime((f * duration).toLong()),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-26).dp),
            )
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (duration > 0) onSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrub(true)
                        },
                        onDragEnd = {
                            dragFraction?.let { if (duration > 0) onSeek((it * duration).toLong()) }
                            dragFraction = null
                            onScrub(false)
                        },
                        onDragCancel = {
                            dragFraction = null
                            onScrub(false)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val h = thickness.dp.toPx()
            val y = size.height / 2 - h / 2
            val radius = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color(0x40FFFFFF), Offset(0f, y), Size(size.width, h), radius)
            if (duration > 0) {
                drawRoundRect(Color(0x66FFFFFF), Offset(0f, y), Size(size.width * (buffered.toFloat() / duration).coerceIn(0f, 1f), h), radius)
            }
            drawRoundRect(HarborColors.Accent, Offset(0f, y), Size(size.width * shown.coerceIn(0f, 1f), h), radius)
            drawCircle(Color.White, radius = (if (dragFraction != null) 9.dp else 7.dp).toPx(), center = Offset(size.width * shown.coerceIn(0f, 1f), size.height / 2))
        }
    }
}

@Composable
private fun DataWarningPanel(connection: Connection, onPlay: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).clip(RoundedCornerShape(20.dp)).background(HarborColors.Surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x33FB923C)), contentAlignment = Alignment.Center) {
                Icon(HIcons.Cellular, null, tint = HarborColors.Warn, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (connection == Connection.Cellular) R.string.player_data_warning_cellular else R.string.player_data_warning_metered),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.player_data_warning_body),
                style = MaterialTheme.typography.bodyMedium,
                color = HarborColors.TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(stringResource(R.string.common_cancel), null, onCancel)
                PrimaryButton(stringResource(R.string.player_play_anyway), HIcons.Play, onPlay)
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(20.dp)).background(HarborColors.Surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x33FB923C)), contentAlignment = Alignment.Center) {
                Icon(HIcons.Info, null, tint = HarborColors.Warn, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.player_error_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextDim)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(stringResource(R.string.player_other_stream), HIcons.Back, onBack)
                PrimaryButton(stringResource(R.string.common_retry), HIcons.Refresh, onRetry)
            }
        }
    }
}
