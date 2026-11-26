package com.synrg.harbor.ui.downloads

import android.os.StatFs
import androidx.compose.foundation.background
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.data.Img
import com.synrg.harbor.downloads.DownloadItem
import com.synrg.harbor.downloads.DownloadSpeed
import com.synrg.harbor.downloads.DownloadStatus
import com.synrg.harbor.downloads.formatBytes
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.RoundIconButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors

/** Downloaded and downloading movies and episodes: tap a finished one to watch it offline. */
@Composable
fun DownloadsScreen(bottomPadding: Dp, onBack: () -> Unit, onPlay: (DownloadItem) -> Unit) {
    val graph = LocalContext.current.graph
    val data by graph.downloads.items.collectAsStateWithLifecycle()
    val speeds by graph.downloads.speed.collectAsStateWithLifecycle()
    val items = data.items.sortedByDescending { it.addedAt }

    Column(Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(HIcons.Back, "Volver", onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Descargas", style = MaterialTheme.typography.headlineSmall)
                val used = items.sumOf { it.localFile?.length() ?: 0L }
                val free = remember(items.size) { runCatching { StatFs(graph.downloads.dir.path).availableBytes }.getOrDefault(0L) }
                Text(
                    "Ocupan ${formatBytes(used)} · quedan ${formatBytes(free)} libres",
                    style = MaterialTheme.typography.bodySmall,
                    color = HarborColors.TextDim,
                )
            }
        }
        if (items.isEmpty()) {
            MessageBlock(
                "No hay descargas",
                "En la lista de fuentes de una película o episodio, tocá ⬇ para guardarlo y verlo sin internet.",
                modifier = Modifier.padding(top = 60.dp),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 16.dp, bottom = bottomPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { item ->
                DownloadRow(
                    item, speeds[item.id],
                    onClick = { if (item.localFile != null) onPlay(item) },
                    onPause = { graph.downloads.pause(item.id) },
                    onResume = { graph.downloads.resume(item.id) },
                    onRemove = { graph.downloads.remove(item.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    speed: DownloadSpeed?,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val done = item.status == DownloadStatus.Done
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(HarborColors.SurfaceHigh)) {
            AsyncImage(
                model = Img.still(item.still, "w300") ?: Img.backdrop(item.backdrop, "w300"),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (done) HIcons.Play else HIcons.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (!done) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))
                        .drawBehind { drawRect(HarborColors.Accent, size = size.copy(width = size.width * item.fraction)) },
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.season != null && item.episode != null) {
                Text(
                    "T${item.season} · E${item.episode}" + (item.episodeTitle?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = HarborColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                statusLine(item, speed),
                style = MaterialTheme.typography.labelSmall,
                color = when (item.status) {
                    DownloadStatus.Failed -> HarborColors.Warn
                    DownloadStatus.Downloading -> HarborColors.Accent
                    else -> HarborColors.TextFaint
                },
                maxLines = 2,
            )
        }
        Box {
            Icon(
                HIcons.MoreVert, "Opciones", tint = HarborColors.TextDim,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }.padding(9.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = HarborColors.SurfaceHigh) {
                when (item.status) {
                    DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.WaitingWifi -> DropdownMenuItem(
                        text = { Text("Pausar") },
                        leadingIcon = { Icon(HIcons.Pause, null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onPause() },
                    )
                    DownloadStatus.Paused, DownloadStatus.Failed -> DropdownMenuItem(
                        text = { Text(if (item.status == DownloadStatus.Failed) "Reintentar" else "Reanudar") },
                        leadingIcon = { Icon(HIcons.Download, null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onResume() },
                    )
                    DownloadStatus.Done -> {}
                }
                DropdownMenuItem(
                    text = { Text(if (done) "Borrar descarga" else "Cancelar descarga") },
                    leadingIcon = { Icon(HIcons.Delete, null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

private fun statusLine(item: DownloadItem, speed: DownloadSpeed?): String {
    val sizes = if (item.total > 0) "${formatBytes(item.bytes)} de ${formatBytes(item.total)}" else null
    return when (item.status) {
        DownloadStatus.Done -> listOfNotNull("Descargado", item.localFile?.length()?.let(::formatBytes)).joinToString(" · ")
        DownloadStatus.Downloading -> listOfNotNull(
            "${(item.fraction * 100).toInt()}%",
            speed?.bps?.takeIf { it > 0 }?.let { formatBytes(it) + "/s" },
            speed?.peers?.takeIf { it > 0 }?.let { "$it pares" },
            sizes,
        ).joinToString(" · ")
        DownloadStatus.Queued -> "En espera" + (sizes?.let { " · $it" } ?: "")
        DownloadStatus.WaitingWifi -> "Esperando Wi-Fi" + (sizes?.let { " · $it" } ?: "")
        DownloadStatus.Paused -> "En pausa" + (sizes?.let { " · $it" } ?: "")
        DownloadStatus.Failed -> item.error ?: "No se pudo descargar"
    }
}
