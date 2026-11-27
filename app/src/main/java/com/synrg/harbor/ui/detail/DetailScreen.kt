package com.synrg.harbor.ui.detail

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.R
import com.synrg.harbor.data.CastMember
import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Saga
import com.synrg.harbor.data.WatchProviders
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import android.widget.Toast
import com.synrg.harbor.downloads.DownloadItem
import com.synrg.harbor.downloads.DownloadStatus
import com.synrg.harbor.episodes.EpisodeAlerts
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.ui.components.GlassButton
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.PosterCard
import com.synrg.harbor.ui.components.PosterRow
import com.synrg.harbor.ui.components.PosterShape
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
    onPerson: (CastMember, String?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val inWatchlist by vm.inWatchlist.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val streams by vm.streams.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val downloadingToast = stringResource(R.string.detail_downloading_toast)
    val lookingToast = stringResource(R.string.detail_season_looking)
    val seasonBusy by vm.seasonBusy.collectAsStateWithLifecycle()
    var confirmSeason by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(vm) { vm.playRequests.collect { onPlay(it) } }
    LaunchedEffect(vm) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

    Box(Modifier.fillMaxSize().background(HarborColors.Bg)) {
        val details = state.details
        when {
            details != null -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header", contentType = "header") {
                    Header(details, state.partial, listState.firstVisibleItemIndex == 0, { listState.firstVisibleItemScrollOffset })
                }
                item(key = "actions", contentType = "actions") {
                    val movieWatched = ProgressEntry.key(MediaType.Movie, vm.id) in watched
                    Actions(
                        details, vm.mainButtonLabel(progress), inWatchlist,
                        onPlay = vm::playMain,
                        onSources = if (vm.resumesDirectly(progress)) vm::chooseSource else null,
                        onToggleList = vm::toggleWatchlist,
                        movieWatched = movieWatched.takeIf { details.item.type == MediaType.Movie && !state.partial },
                        onToggleWatched = { vm.setMovieWatched(!movieWatched) },
                        downloadNote = downloads[ProgressEntry.key(MediaType.Movie, vm.id)]?.takeIf { details.item.type == MediaType.Movie }?.let { downloadLabel(it) },
                        alerts = alerts,
                        onToggleAlerts = {
                            if (alerts == false && Build.VERSION.SDK_INT >= 33 && !EpisodeAlerts.permitted(context)) {
                                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.toggleAlerts()
                        },
                    )
                }
                item(key = "overview", contentType = "overview") { Overview(details) }
                details.providers?.takeUnless { it.isEmpty }?.let { providers ->
                    item(key = "providers", contentType = "providers") { WhereToWatch(providers) }
                }
                if (details.item.type == MediaType.Tv && details.seasons.isNotEmpty()) {
                    val episodes = state.episodes
                    item(key = "seasons", contentType = "seasons") {
                        val released = episodes.orEmpty().filter { it.released }
                        val seasonWatched = released.isNotEmpty() &&
                            released.all { ProgressEntry.key(MediaType.Tv, vm.id, it.season, it.number) in watched }
                        SeasonTabs(
                            details, state.season, vm::selectSeason,
                            seasonWatched = seasonWatched.takeIf { released.isNotEmpty() },
                            onToggleSeason = { vm.setEpisodesWatched(released, !seasonWatched) },
                            downloadBusy = seasonBusy,
                            onDownloadSeason = if (released.isNotEmpty()) ({ confirmSeason = state.season }) else null,
                        )
                    }
                    if (episodes == null) {
                        item(key = "episodes-loading") {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            }
                        }
                    } else {
                        items(episodes, key = { "ep-${it.season}-${it.number}" }, contentType = { "episode" }) { ep ->
                            val key = ProgressEntry.key(MediaType.Tv, vm.id, ep.season, ep.number)
                            val epWatched = key in watched
                            EpisodeRow(
                                ep, progress[key], epWatched, downloads[key]?.let { downloadLabel(it) },
                                onClick = { vm.openEpisode(ep) },
                                onToggleWatched = { vm.setEpisodesWatched(listOf(ep), !epWatched) },
                            )
                        }
                    }
                }
                state.saga?.let { saga ->
                    item(key = "saga", contentType = "row") { SagaRow(saga, vm.id, onOpen) }
                }
                if (details.cast.isNotEmpty()) {
                    item(key = "cast", contentType = "cast") { CastRow(details) { onPerson(it, details.imdbId) } }
                }
                if (details.recommendations.isNotEmpty()) {
                    item(key = "recs", contentType = "row") { PosterRow(stringResource(R.string.detail_more_like_this), details.recommendations, onOpen) }
                }
            }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MessageBlock(stringResource(R.string.detail_error_title), state.error ?: "", action = { PrimaryButton(stringResource(R.string.common_retry), HIcons.Refresh, vm::load) })
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp)
            }
        }
        RoundIconButton(
            HIcons.Back, stringResource(R.string.detail_back), onBack,
            modifier = Modifier.statusBarsPadding().padding(start = 12.dp, top = 6.dp),
        )
    }

    confirmSeason?.let { season ->
        val count = state.episodes.orEmpty().count { it.released }
        AlertDialog(
            onDismissRequest = { confirmSeason = null },
            containerColor = HarborColors.Surface,
            title = { Text(stringResource(R.string.detail_download_season_title, season)) },
            text = { Text(pluralStringResource(R.plurals.detail_download_season_body, count, count), color = HarborColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSeason = null
                    vm.downloadSeason()
                    Toast.makeText(context, lookingToast, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.detail_download_season_go), color = HarborColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSeason = null }) { Text(stringResource(R.string.common_cancel), color = HarborColors.TextDim) }
            },
        )
    }

    val ui = streams
    if (ui != null) {
        val downloaded = downloads[ProgressEntry.key(vm.type, vm.id, ui.target.season, ui.target.episode)]
        StreamsSheet(
            ui = ui,
            title = state.details?.item?.title.orEmpty(),
            downloaded = downloaded,
            onPlayDownloaded = { item ->
                vm.closeStreams()
                onPlay(vm.playDownloaded(item))
            },
            onDownload = { saved ->
                vm.download(ui.target, saved)
                Toast.makeText(context, downloadingToast, Toast.LENGTH_SHORT).show()
            },
            onDismiss = vm::closeStreams,
            onCancelAuto = vm::cancelAuto,
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
        val runtime = details.runtime?.takeIf { it > 0 }?.let {
            if (it >= 60) stringResource(R.string.detail_runtime_hm, it / 60, it % 60) else stringResource(R.string.detail_runtime_min, it)
        }
        val seasons = pluralStringResource(R.plurals.detail_seasons, details.seasons.size, details.seasons.size)
        val parts = buildList {
            details.item.year?.let(::add)
            if (details.item.type == MediaType.Movie) {
                runtime?.let(::add)
            } else if (!partial) {
                add(seasons)
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
private fun Actions(
    details: Details,
    mainLabel: String,
    inWatchlist: Boolean,
    onPlay: () -> Unit,
    onSources: (() -> Unit)?,
    onToggleList: () -> Unit,
    movieWatched: Boolean?,
    onToggleWatched: () -> Unit,
    downloadNote: String?,
    alerts: Boolean?,
    onToggleAlerts: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(mainLabel, HIcons.Play, onPlay, modifier = Modifier.weight(1f))
            // Resuming skips the sources list; this is the way back to it (dead torrent, other quality).
            if (onSources != null) GlassButton(stringResource(R.string.detail_streams), null, onSources)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                if (inWatchlist) stringResource(R.string.detail_in_list) else stringResource(R.string.detail_add_to_list),
                if (inWatchlist) HIcons.Check else HIcons.Add,
                onToggleList,
                modifier = Modifier.weight(1f),
            )
            if (movieWatched != null) {
                GlassButton(if (movieWatched) stringResource(R.string.watched_movie_done) else stringResource(R.string.watched_movie_mark), if (movieWatched) HIcons.CheckCircle else HIcons.Visibility, onToggleWatched)
            }
            if (alerts != null) {
                RoundIconButton(
                    if (alerts) HIcons.Bell else HIcons.BellOff,
                    if (alerts) stringResource(R.string.detail_alerts_off) else stringResource(R.string.detail_alerts_on),
                    onToggleAlerts,
                    size = 46.dp,
                    tint = if (alerts) HarborColors.Accent else Color.White,
                )
            }
        }
        if (downloadNote != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(HIcons.DownloadDone, null, tint = HarborColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(downloadNote, style = MaterialTheme.typography.labelMedium, color = HarborColors.Accent)
            }
        }
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
private fun SeasonTabs(
    details: Details,
    selected: Int?,
    onSelect: (Int) -> Unit,
    seasonWatched: Boolean?,
    onToggleSeason: () -> Unit,
    downloadBusy: Boolean,
    onDownloadSeason: (() -> Unit)?,
) {
    Column(Modifier.padding(top = 26.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.common_episodes), Modifier.weight(1f))
            if (downloadBusy) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            } else if (onDownloadSeason != null) {
                Icon(
                    HIcons.Download, stringResource(R.string.detail_download_season), tint = HarborColors.TextDim,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDownloadSeason).padding(8.dp),
                )
            }
            if (seasonWatched != null) {
                Row(
                    Modifier.padding(end = 12.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onToggleSeason)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (seasonWatched) HIcons.CheckCircle else HIcons.Visibility, null,
                        tint = if (seasonWatched) HarborColors.Accent else HarborColors.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (seasonWatched) stringResource(R.string.watched_season_done) else stringResource(R.string.watched_season_mark),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (seasonWatched) HarborColors.Accent else HarborColors.TextDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // The selected season in view (a long series opened at its latest season, from a new-episode alert).
        val tabsState = rememberLazyListState()
        LaunchedEffect(selected) {
            val index = details.seasons.indexOfFirst { it.number == selected }
            if (index > 0) tabsState.animateScrollToItem(index)
        }
        LazyRow(state = tabsState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(details.seasons, key = { it.number }) { season ->
                val active = season.number == selected
                Text(
                    if (details.seasons.size > 1 || season.name.isBlank()) stringResource(R.string.common_season_n, season.number) else season.name,
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
private fun EpisodeRow(ep: Episode, progress: ProgressEntry?, watched: Boolean, download: String?, onClick: () -> Unit, onToggleWatched: () -> Unit) {
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
            if (watched || (progress != null && progress.fraction > 0.01f)) {
                val fraction = if (watched) 1f else progress?.fraction ?: 0f
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))
                        .drawBehind { drawRect(HarborColors.Accent, size = size.copy(width = size.width * fraction)) },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.number}. ${ep.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                ep.runtime?.takeIf { it > 0 }?.let { stringResource(R.string.detail_runtime_min, it) },
                if (!ep.released) ep.airDate?.let { stringResource(R.string.detail_airs_on, it) } else null,
                if (watched) stringResource(R.string.watched_episode_done) else null,
                download,
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = if (watched) HarborColors.Accent else HarborColors.TextFaint)
            }
            if (ep.overview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(ep.overview, style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ep.released) {
            // The watched check: tap to mark or unmark, like the eye in Stremio.
            Box(
                Modifier.padding(start = 4.dp).size(44.dp).clip(CircleShape).clickable(onClick = onToggleWatched),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (watched) HarborColors.Accent else Color.Transparent)
                        .border(1.5.dp, if (watched) HarborColors.Accent else Color(0x59FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        HIcons.Check,
                        if (watched) stringResource(R.string.watched_unmark) else stringResource(R.string.watched_mark),
                        tint = if (watched) HarborColors.OnAccent else HarborColors.TextFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** "Downloaded", "Downloading 45%", "Download paused"… */
@Composable
private fun downloadLabel(item: DownloadItem): String = when (item.status) {
    DownloadStatus.Done -> if (item.type == MediaType.Movie) stringResource(R.string.detail_download_done_movie) else stringResource(R.string.detail_download_done)
    DownloadStatus.Downloading -> stringResource(R.string.detail_download_progress, (item.fraction * 100).toInt())
    DownloadStatus.Queued -> stringResource(R.string.detail_download_queued)
    DownloadStatus.WaitingWifi -> stringResource(R.string.detail_download_waiting_wifi)
    DownloadStatus.Paused -> stringResource(R.string.detail_download_paused)
    DownloadStatus.Failed -> stringResource(R.string.detail_download_failed)
}

@Composable
private fun WhereToWatch(providers: WatchProviders) {
    val uriHandler = LocalUriHandler.current
    val open = { providers.link?.let { runCatching { uriHandler.openUri(it) } } }
    Column(Modifier.padding(top = 22.dp)) {
        SectionTitle(stringResource(R.string.detail_where_to_watch))
        Spacer(Modifier.height(4.dp))
        listOf(
            R.string.providers_stream to providers.stream,
            R.string.providers_free to providers.free,
            R.string.providers_rent to providers.rent,
            R.string.providers_buy to providers.buy,
        ).filter { it.second.isNotEmpty() }.forEach { (label, list) ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextDim,
                    modifier = Modifier.padding(start = 16.dp).width(92.dp),
                )
                LazyRow(contentPadding = PaddingValues(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { provider ->
                        AsyncImage(
                            model = Img.logo(provider.logoPath, "w92"),
                            contentDescription = provider.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHigh)
                                .clickable { open() },
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.providers_source),
            style = MaterialTheme.typography.labelSmall,
            color = HarborColors.TextFaint,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp),
        )
    }
}

@Composable
private fun SagaRow(saga: Saga, currentId: Int, onOpen: (MediaItem) -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        SectionTitle(saga.name)
        Spacer(Modifier.height(10.dp))
        val listState = rememberLazyListState()
        // The movie on screen in view, so a long saga opens where it is.
        LaunchedEffect(saga) {
            val index = saga.parts.indexOfFirst { it.id == currentId }
            if (index > 1) listState.scrollToItem(index - 1)
        }
        LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(saga.parts, key = { it.id }) { part ->
                val current = part.id == currentId
                Column(Modifier.width(112.dp)) {
                    PosterCard(
                        part,
                        onClick = { if (!current) onOpen(part) },
                        modifier = if (current) Modifier.border(2.dp, HarborColors.Accent, PosterShape) else Modifier,
                    )
                    Text(
                        if (current) stringResource(R.string.detail_saga_here) else part.year.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (current) HarborColors.Accent else HarborColors.TextFaint,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CastRow(details: Details, onPerson: (CastMember) -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        SectionTitle(stringResource(R.string.detail_cast))
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(details.cast, key = { it.id }) { person ->
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable { onPerson(person) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
