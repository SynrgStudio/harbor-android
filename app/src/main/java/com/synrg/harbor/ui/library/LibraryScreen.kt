package com.synrg.harbor.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.graph
import com.synrg.harbor.ui.components.ContinueCard
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.PosterCard
import com.synrg.harbor.ui.theme.HarborColors

@Composable
fun LibraryScreen(bottomPadding: Dp, onOpen: (MediaItem) -> Unit, onResume: (ProgressEntry) -> Unit) {
    val graph = LocalContext.current.graph
    val items by remember { graph.watchlist.items }.collectAsState(initial = emptyList())
    val watching by remember { graph.progress.continueWatching }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding()) {
        Text("Mi lista", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        if (items.isEmpty() && watching.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                MessageBlock(
                    "Todavía no guardaste nada",
                    "Tocá “Agregar a mi lista” en cualquier película o serie y aparece acá.",
                    modifier = Modifier.padding(top = 60.dp),
                )
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (watching.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "cw") {
                    Column {
                        Text("Seguir viendo", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 16.dp)) {
                            items(watching, key = { it.key }) { ContinueCard(it, onClick = { onResume(it) }) }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (items.isNotEmpty()) Text("Guardados", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                PosterCard(item, onClick = { onOpen(item) }, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
