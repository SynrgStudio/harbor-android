package com.synrg.harbor.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.synrg.harbor.BuildConfig
import com.synrg.harbor.R
import com.synrg.harbor.core.Strings
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import java.util.Locale

private data class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val language: String?, val selected: Boolean)

private fun describe(format: Format, type: Int): String {
    val lang = format.language?.takeIf { it != "und" && it.isNotBlank() }?.let { Languages.label(it) }
    val extra = buildList {
        format.label?.takeIf { it.isNotBlank() && !it.equals(lang, true) }?.let(::add)
        if (type == C.TRACK_TYPE_AUDIO) {
            when (format.channelCount) {
                1 -> add(Strings.get(R.string.tracks_mono))
                2 -> add(Strings.get(R.string.tracks_stereo))
                6 -> add("5.1")
                8 -> add("7.1")
            }
            format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)?.replace("VND.DTS", "DTS")?.replace("EAC3", "E-AC3")?.let(::add)
        }
        if (type == C.TRACK_TYPE_TEXT && (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) add(Strings.get(R.string.tracks_forced))
    }
    return listOfNotNull(lang ?: if (extra.isEmpty()) Strings.get(R.string.tracks_generic) else null).plus(extra).joinToString(" · ")
}

private fun choices(tracks: Tracks, type: Int): List<TrackChoice> =
    tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            TrackChoice(g, i, describe(f, type), f.language, g.isTrackSelected(i))
        }
    }

private enum class Tab(@StringRes val label: Int) { Audio(R.string.tracks_audio), Subtitles(R.string.tracks_subtitles), Speed(R.string.tracks_speed) }

/** Side panel inside the player window, so the video stays full screen while choosing. */
@Composable
fun TracksPanel(player: Player, status: PlayerStatus, controller: PlayerActivity, onClose: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { version++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val tracks = remember(version) { player.currentTracks }
    val textDisabled = remember(version) { player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) }
    var tab by remember { mutableStateOf(Tab.Subtitles) }
    // The internet subtitle in use, if any: its track id carries the "ext-" id we gave it.
    val activeExternalUrl = remember(version) {
        val selectedId = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).id } }
        player.currentMediaItem?.localConfiguration?.subtitleConfigurations
            ?.firstOrNull { cfg -> val id = cfg.id; id != null && selectedId?.contains(id) == true }?.uri?.toString()
    }
    var speed by remember { mutableStateOf(player.playbackParameters.speed) }
    val savedTag = stringResource(R.string.subs_saved)

    fun select(choice: TrackChoice, type: Int, position: Int = 0) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
            .build()
        if (type == C.TRACK_TYPE_AUDIO) {
            choice.language?.takeIf { it != "und" && it.isNotBlank() }?.let(controller::rememberAudio)
        } else {
            controller.rememberEmbeddedSubtitle(choice.group.getTrackFormat(choice.index), position)
        }
        version++
    }

    PanelFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(HarborColors.SurfaceHigh).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Tab.entries.forEach { t ->
                    val active = t == tab
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (active) HarborColors.SurfaceHighest else Color.Transparent)
                            .clickable { tab = t }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(t.label), style = MaterialTheme.typography.labelLarge, color = if (active) HarborColors.Accent else HarborColors.TextDim)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                HIcons.Close, stringResource(R.string.common_close), tint = HarborColors.TextDim,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClose).padding(9.dp),
            )
        }
        Spacer(Modifier.size(10.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth()) {
            when (tab) {
                Tab.Audio -> {
                    val audio = choices(tracks, C.TRACK_TYPE_AUDIO)
                    if (audio.isEmpty()) item { Hint(stringResource(R.string.tracks_audio_not_loaded)) }
                    items(audio) { c -> Option(c.label, c.selected) { select(c, C.TRACK_TYPE_AUDIO) } }
                }
                Tab.Subtitles -> {
                    if (!textDisabled) item { SubtitleSync(status.subtitleDelayMs, controller::setSubtitleDelay) }
                    item {
                        Option(stringResource(R.string.subs_off), textDisabled) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            controller.rememberSubtitlesOff()
                            version++
                        }
                    }
                    val embedded = choices(tracks, C.TRACK_TYPE_TEXT).filterNot { it.group.getTrackFormat(it.index).id?.contains("ext-") == true }
                    itemsIndexed(embedded) { i, c ->
                        Option(c.label, c.selected && !textDisabled) { select(c, C.TRACK_TYPE_TEXT, i) }
                    }
                    item { SectionLabel(stringResource(R.string.subs_online)) }
                    val external = status.externalSubs
                    when {
                        status.loadingSubs -> item { CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp) }
                        external.isNullOrEmpty() -> item { Hint(stringResource(R.string.subs_none_online)) }
                        else -> {
                            // Same-language entries get a number so they can be told apart.
                            val seen = HashMap<String, Int>()
                            val labelled = external.take(60).map { sub ->
                                val name = Languages.label(sub.lang)
                                val n = (seen[name] ?: 0) + 1
                                seen[name] = n
                                sub to "$name $n" + if (sub.url.startsWith("file://")) " · $savedTag" else ""
                            }
                            items(labelled, key = { it.first.url }) { (sub, label) ->
                                Option(label, sub.url == activeExternalUrl && !textDisabled) {
                                    controller.selectExternalSubtitle(sub)
                                    onClose()
                                }
                            }
                        }
                    }
                }
                Tab.Speed -> {
                    items(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { s ->
                        Option(if (s == 1f) stringResource(R.string.tracks_speed_normal) else String.format(Locale.ROOT, "%sx", s.toString().removeSuffix(".0")), speed == s) {
                            player.playbackParameters = PlaybackParameters(s)
                            speed = s
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(Color(0xF2101318))
            .pointerInput(Unit) { detectTapGestures { } } // keep taps from reaching the video
            .padding(top = 20.dp, start = 20.dp, end = 20.dp),
        content = content,
    )
}

/** "+0.5 s" in English, "+0,5 s" in Spanish. */
private fun formatDelay(ms: Long): String = String.format(Strings.locale, "%+.1f s", ms / 1000.0).replace('-', '−')

/** Moves the subtitles against the video in small steps, while watching them over the picture. */
@Composable
private fun SubtitleSync(delayMs: Long, onChange: (Long) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(HarborColors.SurfaceHigh).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.subs_sync), style = MaterialTheme.typography.labelLarge, color = HarborColors.Text)
            Spacer(Modifier.weight(1f))
            Text(
                if (delayMs == 0L) stringResource(R.string.subs_no_offset) else formatDelay(delayMs),
                style = MaterialTheme.typography.labelLarge,
                color = if (delayMs == 0L) HarborColors.TextDim else HarborColors.Accent,
            )
            if (delayMs != 0L) {
                Text(
                    stringResource(R.string.subs_reset),
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextDim,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { onChange(0) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-500L, -100L, 100L, 500L).forEach { step ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHighest)
                        .clickable { onChange(delayMs + step) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatDelay(step), style = MaterialTheme.typography.labelLarge, color = HarborColors.Text)
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.subs_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = HarborColors.TextFaint,
        )
    }
}

/** Sleep timer choices: a few durations, or stop when this video ends. */
@Composable
fun SleepPanel(current: SleepTimer?, isEpisode: Boolean, onPick: (SleepTimer?) -> Unit, onClose: () -> Unit) {
    PanelFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.titleMedium, color = HarborColors.Text, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Icon(
                HIcons.Close, stringResource(R.string.common_close), tint = HarborColors.TextDim,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClose).padding(9.dp),
            )
        }
        Hint(stringResource(R.string.sleep_hint))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth()) {
            item { Option(stringResource(R.string.sleep_off), current == null) { onPick(null) } }
            // Debug builds add a 1-minute timer to try it out.
            items(listOfNotNull(1.takeIf { BuildConfig.DEBUG }, 15, 30, 45, 60, 90)) { minutes ->
                val label = when {
                    minutes < 60 -> pluralStringResource(R.plurals.sleep_minutes, minutes, minutes)
                    minutes == 60 -> stringResource(R.string.sleep_one_hour)
                    else -> stringResource(R.string.sleep_hour_and_minutes, minutes - 60)
                }
                Option(label, (current as? SleepTimer.At)?.minutes == minutes) {
                    onPick(SleepTimer.At(SystemClock.elapsedRealtime() + minutes * 60_000L, minutes))
                }
            }
            item {
                Option(stringResource(if (isEpisode) R.string.sleep_end_of_episode else R.string.sleep_end_of_movie), current == SleepTimer.EndOfVideo) {
                    onPick(SleepTimer.EndOfVideo)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = HarborColors.TextFaint, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim, modifier = Modifier.padding(8.dp))
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) HarborColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(HIcons.Check, null, tint = HarborColors.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) HarborColors.Text else HarborColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
