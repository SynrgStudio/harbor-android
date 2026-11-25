package com.synrg.harbor.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synrg.harbor.core.AppGraph
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.ui.components.PosterCard
import com.synrg.harbor.ui.components.RoundIconButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class SearchState(val query: String = "", val loading: Boolean = false, val results: List<MediaItem> = emptyList(), val suggestions: Boolean = true)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val graph: AppGraph) : ViewModel() {
    val query = MutableStateFlow("")

    val state: StateFlow<SearchState> = query
        .map { it.trim() }
        .debounce { if (it.isEmpty()) 0L else 320L }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.length < 2) {
                    emit(SearchState(q, loading = true))
                    val trending = runCatching { graph.meta.trending() }.getOrDefault(emptyList())
                    emit(SearchState(q, results = trending.filter { it.posterPath != null }, suggestions = true))
                } else {
                    emit(SearchState(q, loading = true, suggestions = false))
                    val found = runCatching { graph.meta.search(q) }.getOrDefault(emptyList())
                    emit(SearchState(q, results = found.filter { it.posterPath != null }, suggestions = false))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState(loading = true))
}

@Composable
fun SearchScreen(vm: SearchViewModel, bottomPadding: Dp, onOpen: (MediaItem) -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().background(HarborColors.Bg).statusBarsPadding()) {
        Text("Buscar", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp)
                .clip(RoundedCornerShape(25.dp)).background(HarborColors.SurfaceHigh)
                .border(1.dp, HarborColors.Outline, RoundedCornerShape(25.dp))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HIcons.Search, null, tint = HarborColors.TextDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Películas, series, anime…", style = MaterialTheme.typography.bodyLarge, color = HarborColors.TextFaint)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = HarborColors.Text),
                    cursorBrush = SolidColor(HarborColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                RoundIconButton(HIcons.Close, "Borrar", onClick = { vm.query.value = "" }, size = 36.dp)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Text(
                    when {
                        state.suggestions -> "Tendencias"
                        state.loading -> "Buscando…"
                        state.results.isEmpty() -> "Sin resultados para “${state.query}”"
                        else -> "Resultados"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = HarborColors.TextDim,
                )
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            }
            items(state.results, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
                PosterCard(item, onClick = { onOpen(item) }, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
