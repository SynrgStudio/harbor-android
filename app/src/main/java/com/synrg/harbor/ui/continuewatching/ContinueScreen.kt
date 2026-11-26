package com.synrg.harbor.ui.continuewatching

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
import java.util.Locale

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
        Text("Seguir viendo", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        val list = entries ?: return@Column
        // New episodes already watched (or being watched) here are not news.
        val recent = calendar.recent.filter { ep -> all[ep.key]?.let { it.finished || it.positionMs > 0 } != true }
        val upcoming = calendar.upcoming
        if (list.isEmpty() && recent.isEmpty() && upcoming.isEmpty()) {
            MessageBlock(
                "Nada a medio ver",
                "Lo que empieces a ver aparece acá, también lo que mires en Stremio o en Harbor de la PC. " +
                    "Los episodios nuevos de tus series también.",
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
                    Text("Nada a medio ver por ahora.", style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextDim, modifier = Modifier.padding(start = 4.dp))
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
                item(key = "recent-title") { SectionHeader("Episodios nuevos") }
                items(recent, key = { "new-${it.key}" }, contentType = { "calendar" }) { ep ->
                    CalendarRow(ep, released = true, onClick = { onShow(ep) })
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "upcoming-title") { SectionHeader("Próximamente") }
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
                    "NUEVO",
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
                "T${ep.season} · E${ep.episode}" + (ep.name?.let { " — $it" } ?: ""),
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

private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("es"))
private val DAY_MONTH = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.forLanguageTag("es"))

/** "Salió hoy", "Salió el martes"; "Sale mañana", "Sale el jueves 4 dic". */
private fun airLabel(millis: Long, released: Boolean): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), date.toLocalDate())
    val verb = if (released) "Salió" else "Sale"
    return when {
        days == 0L -> "$verb hoy"
        days == -1L -> "Salió ayer"
        days == 1L -> "Sale mañana"
        days in -6L..6L -> "$verb el ${date.format(WEEKDAY)}"
        else -> "$verb el ${date.format(DAY_MONTH).removeSuffix(".")}"
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
                    "T${entry.season} · E${entry.episode}" + (entry.episodeTitle?.let { " — $it" } ?: ""),
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
                    if (entry.remainingMin > 0) "Quedan ${entry.remainingMin} min" else "Casi terminado",
                    ago(entry.updatedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = HarborColors.TextFaint,
                maxLines = 1,
            )
        }
        Box {
            Icon(
                HIcons.MoreVert, "Opciones", tint = HarborColors.TextDim,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }.padding(9.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = HarborColors.SurfaceHigh) {
                DropdownMenuItem(
                    text = { Text("Ver ficha") },
                    leadingIcon = { Icon(HIcons.Info, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onDetails() },
                )
                DropdownMenuItem(
                    text = { Text(if (entry.episode != null) "Marcar episodio como visto" else "Marcar como vista") },
                    leadingIcon = { Icon(HIcons.CheckCircle, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onWatched() },
                )
                DropdownMenuItem(
                    text = { Text("Quitar de Seguir viendo") },
                    leadingIcon = { Icon(HIcons.Close, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es"))

/** "recién", "hace 12 min", "hace 3 h", "ayer", "hace 4 días" or a date; always in Spanish, like the rest of the app. */
private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), LocalDate.now(zone))
    return when {
        minutes < 1 -> "recién"
        minutes < 60 -> "hace $minutes min"
        days == 0L -> "hace ${minutes / 60} h"
        days == 1L -> "ayer"
        days < 7 -> "hace $days días"
        else -> Instant.ofEpochMilli(millis).atZone(zone).format(SHORT_DATE).removeSuffix(".")
    }
}
