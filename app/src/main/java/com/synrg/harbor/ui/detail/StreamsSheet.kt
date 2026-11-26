package com.synrg.harbor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synrg.harbor.data.SavedStream
import com.synrg.harbor.downloads.DownloadItem
import com.synrg.harbor.graph
import com.synrg.harbor.stremio.AddonStreams
import com.synrg.harbor.stremio.Quality
import com.synrg.harbor.stremio.StreamKind
import com.synrg.harbor.stremio.StreamOption
import com.synrg.harbor.ui.components.Badge
import com.synrg.harbor.ui.components.pressScale
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors

private enum class QualityFilter(val label: String, val match: (Quality) -> Boolean) {
    All("Todas", { true }),
    UHD("4K", { it == Quality.UHD }),
    FHD("1080p", { it == Quality.FHD }),
    HD("720p", { it == Quality.HD }),
    Other("Otras", { it == Quality.SD || it == Quality.Unknown }),
}

@Composable
fun StreamsSheet(
    ui: StreamsUi,
    title: String,
    downloaded: DownloadItem?,
    onPlayDownloaded: (DownloadItem) -> Unit,
    onDownload: (SavedStream) -> Unit,
    onDismiss: () -> Unit,
    onPick: (SavedStream) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val torrentsReady = LocalContext.current.graph.torrents.available
    var filter by remember { mutableStateOf(QualityFilter.All) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HarborColors.Surface,
        scrimColor = Color(0x99000000),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = ui.target.season?.let { s -> "Temporada $s · Episodio ${ui.target.episode}" + (ui.target.episodeTitle?.let { " — $it" } ?: "") }
                Text(sub ?: "Elegí una fuente", style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QualityFilter.entries.toList()) { f ->
                    val active = f == filter
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) HarborColors.OnAccent else HarborColors.Text,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (active) HarborColors.Accent else HarborColors.SurfaceHigh)
                            .clickable { filter = f }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                if (downloaded?.localFile != null && filter == QualityFilter.All) {
                    item(key = "downloaded") { DownloadedCard(onClick = { onPlayDownloaded(downloaded) }) }
                }
                val saved = ui.resume?.lastStream?.takeIf { !it.url.orEmpty().startsWith("file://") }
                if (saved != null && filter == QualityFilter.All) {
                    item(key = "resume") {
                        ResumeCard(saved, ui.resume?.positionMs ?: 0L, torrentsReady, onClick = { onPick(saved) })
                    }
                }
                ui.error?.let { msg ->
                    item(key = "error") {
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextDim, modifier = Modifier.padding(8.dp))
                    }
                }
                ui.groups.forEach { group ->
                    item(key = "h-" + group.addon.transportUrl, contentType = "header") { AddonHeader(group, filter) }
                    if (group is AddonStreams.Loaded) {
                        val visible = group.streams.filter { filter.match(it.quality) }
                        items(visible, key = { it.key }, contentType = { "stream" }) { option ->
                            val enabled = option.kind == StreamKind.Direct || (option.kind == StreamKind.Torrent && torrentsReady)
                            StreamCard(
                                option, enabled = enabled, onClick = { onPick(option.saved) },
                                onDownload = if (enabled) ({ onDownload(option.saved) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonHeader(group: AddonStreams, filter: QualityFilter) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(group.addon.name, style = MaterialTheme.typography.labelLarge, color = HarborColors.Text, modifier = Modifier.weight(1f))
        when (group) {
            is AddonStreams.Loading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            is AddonStreams.Failed -> Text("Sin respuesta", style = MaterialTheme.typography.labelSmall, color = HarborColors.Warn)
            is AddonStreams.Loaded -> {
                val n = group.streams.count { filter.match(it.quality) }
                Text(if (n == 1) "1 fuente" else "$n fuentes", style = MaterialTheme.typography.labelSmall, color = HarborColors.TextFaint)
            }
        }
    }
}

@Composable
private fun ResumeCard(saved: SavedStream, positionMs: Long, torrentsReady: Boolean, onClick: () -> Unit) {
    val enabled = saved.url != null || torrentsReady
    val min = positionMs / 60_000
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0C3A52), Color(0xFF102536))))
            .border(1.dp, HarborColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HIcons.Play, null, tint = HarborColors.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Seguir con la misma fuente", style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(saved.label?.takeIf { it.isNotBlank() }, saved.addonName, if (min > 0) "desde el min $min" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = HarborColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadedCard(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0C3A52), Color(0xFF102536))))
            .border(1.dp, HarborColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HIcons.DownloadDone, null, tint = HarborColors.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Ver la descarga", style = MaterialTheme.typography.titleSmall)
            Text("Está en el teléfono: no gasta datos ni necesita internet", style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim)
        }
    }
}

@Composable
private fun QualityBadge(quality: Quality) {
    val (bg, fg) = when (quality) {
        Quality.UHD -> Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFFDE68A))) to Color(0xFF231400)
        Quality.FHD -> Brush.linearGradient(listOf(HarborColors.AccentDeep, HarborColors.Accent)) to HarborColors.OnAccent
        Quality.HD -> Brush.linearGradient(listOf(Color(0xFF475569), Color(0xFF64748B))) to Color.White
        else -> Brush.linearGradient(listOf(Color(0xFF2A2F38), Color(0xFF2A2F38))) to HarborColors.TextDim
    }
    Box(
        Modifier.width(54.dp).height(30.dp).clip(RoundedCornerShape(8.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(quality.label.ifEmpty { "?" }, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun StreamCard(option: StreamOption, enabled: Boolean, onClick: () -> Unit, onDownload: (() -> Unit)?) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(source)
            .clip(RoundedCornerShape(14.dp))
            .background(HarborColors.SurfaceHigh)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(12.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.Top,
    ) {
        QualityBadge(option.quality)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(option.title, style = MaterialTheme.typography.bodyMedium, color = HarborColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (option.cached) Badge("⚡ Caché", color = Color(0x334ADE80), textColor = HarborColors.Good)
                option.tags.forEach { tag ->
                    when (tag) {
                        "CAM" -> Badge(tag, color = Color(0x33FB923C), textColor = HarborColors.Warn)
                        "DV", "HDR" -> Badge(tag, color = Color(0x33FACC15), textColor = HarborColors.Gold)
                        else -> Badge(tag)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val kindIcon = when (option.kind) {
                    StreamKind.Torrent -> HIcons.Magnet
                    StreamKind.Direct -> if (option.cached) HIcons.Bolt else HIcons.Cloud
                    StreamKind.External -> HIcons.Link
                }
                Icon(kindIcon, null, tint = HarborColors.TextFaint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                val meta = listOfNotNull(
                    option.sizeLabel,
                    option.seeders?.let { "$it seeds" },
                    option.source,
                    option.languages.joinToString(" ").takeIf { it.isNotBlank() },
                    if (option.kind == StreamKind.Torrent && !enabled) "torrent: falta el motor" else null,
                ).joinToString("  ·  ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = HarborColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onDownload != null) {
            Icon(
                HIcons.Download, "Descargar",
                tint = HarborColors.TextDim,
                modifier = Modifier.padding(start = 4.dp).size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onDownload).padding(9.dp),
            )
        }
    }
}
