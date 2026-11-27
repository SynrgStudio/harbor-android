package com.synrg.harbor.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.R
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.TitleTime
import com.synrg.harbor.data.WatchLog
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.RoundIconButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Immutable
private data class Stats(
    val seconds: Long,
    val movies: Int,
    val episodes: Int,
    val series: Int,
    /** Seconds per month of the year (12), for the chart; null for "all time". */
    val months: List<Long>?,
    val top: List<TitleTime>,
    val busiestDay: DayOfWeek?,
)

/** This year's numbers, or all time: time from the player's log, finished titles from progress. */
private fun compute(log: WatchLog, progress: Collection<ProgressEntry>, year: Int?): Stats {
    val zone = ZoneId.systemDefault()
    fun inRange(date: LocalDate) = year == null || date.year == year
    val days = log.days.mapNotNull { (day, s) -> runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to s } }.filter { inRange(it.first) }
    val finished = progress.filter { it.finished && inRange(Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate()) }
    val months = year?.let { y -> (1..12).map { m -> days.filter { it.first.monthValue == m && it.first.year == y }.sumOf { it.second } } }
    val byWeekday = days.groupBy { it.first.dayOfWeek }.mapValues { (_, list) -> list.sumOf { it.second } }
    val top = log.titles.values
        .filter { year == null || Instant.ofEpochMilli(it.lastAt).atZone(zone).year == year }
        .sortedByDescending { it.seconds }.take(5)
    return Stats(
        seconds = days.sumOf { it.second },
        movies = finished.count { it.type == MediaType.Movie },
        episodes = finished.count { it.type == MediaType.Tv },
        series = finished.filter { it.type == MediaType.Tv }.map { it.tmdbId }.distinct().size,
        months = months,
        top = top,
        busiestDay = byWeekday.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,
    )
}

/** Zero takes the plural form ("0 movies"); Portuguese would otherwise pick the singular for it. */
private fun plural(count: Int) = if (count == 0) 2 else count

/** "12.5 h", or minutes under an hour. */
private fun formatTime(seconds: Long): String =
    if (seconds < 3_600) "${seconds / 60} min" else String.format(Locale.getDefault(), "%.1f h", seconds / 3_600.0)

@Composable
fun StatsScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val graph = LocalContext.current.graph
    val log by graph.watchLog.state.collectAsStateWithLifecycle()
    val entries by graph.progress.entries.collectAsStateWithLifecycle(initialValue = emptyMap())
    var allTime by rememberSaveable { mutableStateOf(false) }
    val year = LocalDate.now().year
    val stats = remember(log, entries, allTime) { compute(log, entries.values, if (allTime) null else year) }
    val locale = LocalConfiguration.current.locales[0]

    LazyColumn(
        Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(HIcons.Back, stringResource(R.string.detail_back), onBack)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.SurfaceHigh).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(false to stringResource(R.string.stats_this_year, year), true to stringResource(R.string.stats_all_time)).forEach { (value, label) ->
                    val active = allTime == value
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (active) HarborColors.SurfaceHighest else HarborColors.SurfaceHigh)
                            .clickable { allTime = value }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) HarborColors.Accent else HarborColors.TextDim)
                    }
                }
            }
        }
        if (stats.seconds == 0L && stats.movies == 0 && stats.episodes == 0) {
            item(key = "empty") { MessageBlock(stringResource(R.string.stats_empty_title), stringResource(R.string.stats_empty_body), Modifier.padding(top = 32.dp)) }
            return@LazyColumn
        }
        item(key = "numbers") {
            Column(Modifier.padding(top = 16.dp)) {
                BigNumber(formatTime(stats.seconds), stringResource(R.string.stats_time), Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigNumber(stats.movies.toString(), pluralStringResource(R.plurals.stats_movies, plural(stats.movies)), Modifier.weight(1f))
                    BigNumber(stats.episodes.toString(), pluralStringResource(R.plurals.stats_episodes, plural(stats.episodes)), Modifier.weight(1f))
                    BigNumber(stats.series.toString(), pluralStringResource(R.plurals.stats_series, plural(stats.series)), Modifier.weight(1f))
                }
                stats.busiestDay?.let { day ->
                    Text(
                        stringResource(R.string.stats_busiest_day, day.getDisplayName(TextStyle.FULL, locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextDim,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
            }
        }
        stats.months?.takeIf { months -> months.any { it > 0 } }?.let { months ->
            item(key = "months") { MonthChart(months, locale) }
        }
        if (stats.top.isNotEmpty()) {
            item(key = "top-title") {
                Text(stringResource(R.string.stats_top), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp))
            }
            items(stats.top, key = { "${it.type}:${it.tmdbId}" }) { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(MediaItem(t.tmdbId, t.type, t.title, posterPath = t.poster)) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(HarborColors.SurfaceHigh)) {
                        if (t.poster != null) AsyncImage(Img.poster(t.poster, "w154"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatTime(t.seconds), style = MaterialTheme.typography.labelLarge, color = HarborColors.Accent)
                }
            }
        }
        item(key = "note") {
            Text(
                stringResource(R.string.stats_note),
                style = MaterialTheme.typography.bodySmall,
                color = HarborColors.TextFaint,
                modifier = Modifier.padding(top = 24.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(HarborColors.Surface)
            .border(1.dp, HarborColors.Outline, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = HarborColors.Accent, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = HarborColors.TextDim, maxLines = 1)
    }
}

/** Twelve bars, January to December, the tallest one full height. */
@Composable
private fun MonthChart(months: List<Long>, locale: Locale) {
    val max = months.max().coerceAtLeast(1)
    val current = LocalDate.now().monthValue
    Column(Modifier.padding(top = 24.dp)) {
        Text(stringResource(R.string.stats_by_month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val gap = 6.dp.toPx()
            val bar = (size.width - gap * 11) / 12
            months.forEachIndexed { i, s ->
                val h = (s.toFloat() / max) * size.height
                val color = if (i + 1 == current) HarborColors.Accent else HarborColors.Accent.copy(alpha = 0.45f)
                drawRoundRect(HarborColors.SurfaceHigh, Offset(i * (bar + gap), 0f), Size(bar, size.height), CornerRadius(4.dp.toPx()))
                if (h > 0) drawRoundRect(color, Offset(i * (bar + gap), size.height - h), Size(bar, h), CornerRadius(4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Month.entries.forEach { m ->
                Text(
                    m.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.value == current) HarborColors.Accent else HarborColors.TextFaint,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
