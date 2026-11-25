package com.synrg.harbor.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.ui.components.GlassButton
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.PosterRow
import com.synrg.harbor.ui.components.PrimaryButton
import com.synrg.harbor.ui.components.RatingText
import com.synrg.harbor.ui.components.RoundIconButton
import com.synrg.harbor.ui.components.SectionTitle
import com.synrg.harbor.ui.components.pressScale
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors

@Composable
fun DetailScreen(
    vm: DetailViewModel,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val inWatchlist by vm.inWatchlist.collectAsStateWithLifecycle()
    val streams by vm.streams.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(HarborColors.Bg)) {
        val details = state.details
        when {
            details != null -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header", contentType = "header") {
                    Header(details, state.partial, listState.firstVisibleItemIndex == 0, { listState.firstVisibleItemScrollOffset })
                }
                item(key = "actions", contentType = "actions") {
                    Actions(details, vm.mainButtonLabel(progress), inWatchlist, onPlay = vm::playMain, onToggleList = vm::toggleWatchlist)
                }
                item(key = "overview", contentType = "overview") { Overview(details) }
                if (details.item.type == MediaType.Tv && details.seasons.isNotEmpty()) {
                    item(key = "seasons", contentType = "seasons") {
                        SeasonTabs(details, state.season, vm::selectSeason)
                    }
                    val episodes = state.episodes
                    if (episodes == null) {
                        item(key = "episodes-loading") {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            }
                        }
                    } else {
                        items(episodes, key = { "ep-${it.season}-${it.number}" }, contentType = { "episode" }) { ep ->
                            EpisodeRow(ep, progress[ProgressEntry.key(MediaType.Tv, vm.id, ep.season, ep.number)], onClick = { vm.openEpisode(ep) })
                        }
                    }
                }
                if (details.cast.isNotEmpty()) {
                    item(key = "cast", contentType = "cast") { CastRow(details) }
                }
                if (details.recommendations.isNotEmpty()) {
                    item(key = "recs", contentType = "row") { PosterRow("Más como esta", details.recommendations, onOpen) }
                }
            }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MessageBlock("No se pudo abrir", state.error ?: "", action = { PrimaryButton("Reintentar", HIcons.Refresh, vm::load) })
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp)
            }
        }
        RoundIconButton(
            HIcons.Back, "Volver", onBack,
            modifier = Modifier.statusBarsPadding().padding(start = 12.dp, top = 6.dp),
        )
    }

    val ui = streams
    if (ui != null) {
        StreamsSheet(
            ui = ui,
            title = state.details?.item?.title.orEmpty(),
            onDismiss = vm::closeStreams,
            onPick = { saved ->
                vm.playbackRequest(ui.target, saved)?.let {
                    vm.closeStreams()
                    onPlay(it)
                }
            },
        )
    }
}

@Composable
private fun Header(details: Details, partial: Boolean, visible: Boolean, scrollOffset: () -> Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = maxWidth * 1.05f
        Box(Modifier.fillMaxWidth().height(height)) {
            AsyncImage(
                model = Img.backdrop(details.item.backdropPath) ?: Img.poster(details.item.posterPath, "w780"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (visible) {
                        val y = scrollOffset().toFloat()
                        translationY = y * 0.45f
                        alpha = (1f - y / size.height).coerceIn(0f, 1f)
                    }
                },
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x99000000),
                        0.22f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.9f to HarborColors.Bg.copy(alpha = 0.94f),
                        1f to HarborColors.Bg,
                    ),
                ),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (details.logoPath != null) {
                    AsyncImage(
                        model = Img.logo(details.logoPath),
                        contentDescription = details.item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().height(110.dp),
                    )
                } else if (partial) {
                    // The logo may still come: keep its room instead of flashing the plain title.
                    Spacer(Modifier.height(110.dp))
                } else {
                    Text(
                        details.item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                MetaLine(details, partial)
            }
        }
    }
}

@Composable
private fun MetaLine(details: Details, partial: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RatingText(details.item.rating)
        val parts = buildList {
            details.item.year?.let(::add)
            if (details.item.type == MediaType.Movie) {
                details.runtime?.takeIf { it > 0 }?.let { add(if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min") }
            } else if (!partial) {
                add(if (details.seasons.size == 1) "1 temporada" else "${details.seasons.size} temporadas")
            }
        }
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = HarborColors.TextDim)
        details.certification?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = HarborColors.TextDim,
                modifier = Modifier.border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun Actions(details: Details, mainLabel: String, inWatchlist: Boolean, onPlay: () -> Unit, onToggleList: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
        PrimaryButton(mainLabel, HIcons.Play, onPlay, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        GlassButton(
            if (inWatchlist) "En mi lista" else "Agregar a mi lista",
            if (inWatchlist) HIcons.Check else HIcons.Add,
            onToggleList,
            modifier = Modifier.fillMaxWidth(),
        )
        if (details.genres.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                details.genres.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = HarborColors.Accent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Overview(details: Details) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
        details.tagline?.let {
            Text("“$it”", style = MaterialTheme.typography.titleSmall, color = HarborColors.Text)
            Spacer(Modifier.height(8.dp))
        }
        if (details.item.overview.isNotBlank()) {
            Text(
                details.item.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = HarborColors.TextDim,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize().clickable(remember { MutableInteractionSource() }, null) { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun SeasonTabs(details: Details, selected: Int?, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(top = 26.dp, bottom = 6.dp)) {
        SectionTitle("Episodios")
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(details.seasons, key = { it.number }) { season ->
                val active = season.number == selected
                Text(
                    if (details.seasons.size > 1 || season.name.isBlank()) "Temporada ${season.number}" else season.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) HarborColors.OnAccent else HarborColors.Text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) HarborColors.Accent else HarborColors.SurfaceHigh)
                        .clickable { onSelect(season.number) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, progress: ProgressEntry?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = null, enabled = ep.released, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (ep.released) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(138.dp).aspectRatio(16f / 9f).pressScale(source)
                .clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHigh),
        ) {
            if (ep.stillPath != null) {
                AsyncImage(Img.still(ep.stillPath), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) { Icon(HIcons.Play, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            if (progress != null && progress.fraction > 0.01f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))
                        .drawBehind {
                            drawRect(HarborColors.Accent, size = size.copy(width = size.width * if (progress.finished) 1f else progress.fraction))
                        },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.number}. ${ep.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                ep.runtime?.takeIf { it > 0 }?.let { "$it min" },
                if (!ep.released) ep.airDate?.let { "Sale el $it" } else null,
                if (progress?.finished == true) "Visto" else null,
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = if (progress?.finished == true) HarborColors.Accent else HarborColors.TextFaint)
            }
            if (ep.overview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(ep.overview, style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CastRow(details: Details) {
    Column(Modifier.padding(top = 26.dp)) {
        SectionTitle("Reparto")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(details.cast, key = { it.id }) { person ->
                Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(HarborColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                        if (person.profilePath != null) {
                            AsyncImage(Img.profile(person.profilePath), person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(HIcons.Person, null, tint = HarborColors.TextFaint, modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(person.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    person.character?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = HarborColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
