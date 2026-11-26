package com.synrg.harbor.ui.continuewatching

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.synrg.harbor.R
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.episodes.CalendarEpisode
import com.synrg.harbor.stremio.MarkTitle
import com.synrg.harbor.stremio.MarkVideo
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import com.synrg.harbor.core.Strings

/**
 * Everything in progress, newest first, as a vertical list (tap to resume, the menu for details or
 * removal), then the followed series' episodes: out this week, and coming soon.
 */
@Composable
fun ContinueScreen(
    bottomPadding: Dp,
    onResume: (ProgressEntry) -> Unit,
    onDetails: (ProgressEntry) -> Unit,
    onShow: (CalendarEpisode) -> Unit,
) {
    val graph = LocalContext.current.graph
    val entries by remember { graph.progress.inProgress }.collectAsState(initial = null)
    val calendar by graph.episodes.state.collectAsState()
    val all by remember { graph.progress.entries }.collectAsState(initial = emptyMap())
    LaunchedEffect(Unit) { runCatching { graph.episodes.refreshIfStale() } }

    Column(Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding()) {
        Text(stringResource(R.string.continue_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        val list = entries ?: return@Column
        // New episodes already watched (or being watched) here are not news.
        val recent = calendar.recent.filter { ep -> all[ep.key]?.let { it.finished || it.positionMs > 0 } != true }
        val upcoming = calendar.upcoming
        if (list.isEmpty() && recent.isEmpty() && upcoming.isEmpty()) {
            MessageBlock(
                stringResource(R.string.continue_empty_title),
                stringResource(R.string.continue_empty_text),
                modifier = Modifier.padding(top = 60.dp),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 14.dp, bottom = bottomPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (list.isEmpty()) {
                item(key = "nothing") {
                    Text(stringResource(R.string.continue_nothing_now), style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextDim, modifier = Modifier.padding(start = 4.dp))
                }
            }
            items(list, key = { it.key }, contentType = { "row" }) { entry ->
                ContinueRow(
                    entry,
                    onClick = { onResume(entry) },
                    onDetails = { onDetails(entry) },
                    onRemove = {
                        graph.progress.dismiss(entry.type, entry.tmdbId)
                        graph.sync.dismissed(entry)
                    },
                    onWatched = {
                        graph.marks.set(
                            MarkTitle(entry.type, entry.tmdbId, entry.imdbId, entry.title, entry.poster, entry.backdrop),
                            listOf(MarkVideo(entry.season, entry.episode, entry.episodeTitle, entry.still)),
                            watched = true,
                        )
                    },
                )
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-title") { SectionHeader(stringResource(R.string.common_new_episodes)) }
                items(recent, key = { "new-${it.key}" }, contentType = { "calendar" }) { ep ->
                    CalendarRow(ep, released = true, onClick = { onShow(ep) })
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "upcoming-title") { SectionHeader(stringResource(R.string.continue_coming_soon)) }
                items(upcoming, key = { "soon-${it.key}" }, contentType = { "calendar" }) { ep ->
                    CalendarRow(ep, released = false, onClick = { onShow(ep) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
}

/** An episode of a followed series: its picture, show and episode, and when it came or comes out. */
@Composable
private fun CalendarRow(ep: CalendarEpisode, released: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHigh)) {
            AsyncImage(
                model = Img.still(ep.still, "w300") ?: Img.backdrop(ep.backdrop, "w300"),
                contentDescription = ep.show,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (released) {
                Text(
                    stringResource(R.string.continue_new_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = HarborColors.OnAccent,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(HarborColors.Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(ep.show, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.continue_episode_label, ep.season, ep.episode) + (ep.name?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = HarborColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HIcons.Event, null, tint = if (released) HarborColors.Accent else HarborColors.TextFaint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    airLabel(ep.airsAt, released),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (released) HarborColors.Accent else HarborColors.TextFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A date formatter for [skeleton] in the app's language ("EEEEdMMM": "Thursday, Dec 4" / "jueves, 4 dic"). */
private fun localFormatter(skeleton: String): DateTimeFormatter {
    val locale = Strings.locale
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** "Out today", "Out Tuesday"; "Out tomorrow", "Out Thursday, Dec 4". */
@Composable
private fun airLabel(millis: Long, released: Boolean): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), date.toLocalDate())
    return when {
        days == 0L -> stringResource(if (released) R.string.continue_aired_today else R.string.continue_airs_today)
        days == -1L -> stringResource(R.string.continue_aired_yesterday)
        days == 1L -> stringResource(R.string.continue_airs_tomorrow)
        else -> {
            val day = if (days in -6L..6L) {
                date.format(DateTimeFormatter.ofPattern("EEEE", Strings.locale))
            } else {
                date.format(localFormatter("EEEEdMMM")).removeSuffix(".")
            }
            stringResource(if (released) R.string.continue_aired_on else R.string.continue_airs_on, day)
        }
    }
}

@Composable
private fun ContinueRow(entry: ProgressEntry, onClick: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit, onWatched: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHigh),
        ) {
            AsyncImage(
                model = Img.still(entry.still, "w300") ?: Img.backdrop(entry.backdrop, "w300"),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape)
                    .background(Color(0x66000000)).border(1.dp, Color(0x55FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HIcons.Play, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color(0x40FFFFFF))
                    .drawBehind { drawRect(HarborColors.Accent, size = size.copy(width = size.width * entry.fraction)) },
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.season != null && entry.episode != null) {
                Text(
                    stringResource(R.string.continue_episode_label, entry.season, entry.episode) + (entry.episodeTitle?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = HarborColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(
                    if (entry.remainingMin > 0) stringResource(R.string.continue_remaining, entry.remainingMin) else stringResource(R.string.continue_almost_done),
                    ago(entry.updatedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = HarborColors.TextFaint,
                maxLines = 1,
            )
        }
        Box {
            Icon(
                HIcons.MoreVert, stringResource(R.string.continue_options), tint = HarborColors.TextDim,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }.padding(9.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = HarborColors.SurfaceHigh) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.continue_open_details)) },
                    leadingIcon = { Icon(HIcons.Info, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onDetails() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (entry.episode != null) R.string.watched_mark_episode else R.string.watched_mark_movie)) },
                    leadingIcon = { Icon(HIcons.CheckCircle, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onWatched() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.continue_remove)) },
                    leadingIcon = { Icon(HIcons.Close, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

/** "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago" or a short date, in the app's language. */
@Composable
private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), LocalDate.now(zone))
    return when {
        minutes < 1 -> stringResource(R.string.continue_just_now)
        minutes < 60 -> stringResource(R.string.continue_minutes_ago, minutes)
        days == 0L -> stringResource(R.string.continue_hours_ago, minutes / 60)
        days == 1L -> stringResource(R.string.continue_yesterday)
        days < 7 -> pluralStringResource(R.plurals.continue_days_ago, days.toInt(), days)
        else -> Instant.ofEpochMilli(millis).atZone(zone).format(localFormatter("dMMM")).removeSuffix(".")
    }
}
