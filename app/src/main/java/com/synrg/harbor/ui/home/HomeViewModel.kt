package com.synrg.harbor.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synrg.harbor.core.AppGraph
import com.synrg.harbor.data.HeroItem
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.tmdb.CacheOnly
import com.synrg.harbor.tmdb.HomeRowSpec
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import com.synrg.harbor.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@Immutable
data class HomeRow(val key: String, val title: String, val items: List<MediaItem>)

@Immutable
data class HomeState(
    val hero: List<HeroItem> = emptyList(),
    val rows: List<HomeRow?> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    val continueWatching: StateFlow<List<ProgressEntry>> =
        graph.progress.continueWatching.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadJob: Job? = null

    /** Until an account is linked (or the offer is dismissed), home offers it: one login brings everything. */
    val offerStremio: StateFlow<Boolean> = graph.settings.state
        .map { it.stremioAuthKey == null && !it.stremioPromptDismissed }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissStremioOffer() = graph.settings.update { it.copy(stremioPromptDismissed = true) }

    /** The update card, unless this version's offer was dismissed. */
    val update: StateFlow<UpdateState> = combine(graph.updater.state, graph.settings.state) { state, settings ->
        if (state is UpdateState.Available && state.release.version == settings.dismissedUpdate) UpdateState.Idle else state
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdateState.Idle)

    init {
        load()
        viewModelScope.launch { graph.addons.seedDefaultsIfNeeded() }
        // Reload when the metadata language or the TMDB key (which picks the source) changes in Settings.
        viewModelScope.launch {
            graph.settings.state.map { it.language to it.tmdbKey }.distinctUntilChanged().drop(1).collect { load() }
        }
    }

    /**
     * Two passes: first whatever the HTTP disk cache has (even stale) so the screen is full on
     * the first frames, then the network. Rows are replaced only if their content changed.
     */
    fun load() {
        loadJob?.cancel()
        val defs = graph.meta.homeRows()
        _state.value = HomeState(rows = List(defs.size) { null })
        loadJob = viewModelScope.launch {
            withContext(CacheOnly) { loadPass(defs, fromCache = true) }
            loadPass(defs, fromCache = false)
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadPass(defs: List<HomeRowSpec>, fromCache: Boolean) = supervisorScope {
        // The hero keeps what it already shows: swapping slides under the user's finger is jarring.
        if (!fromCache || _state.value.hero.isEmpty()) launch { loadHero(fromCache) }
        defs.forEachIndexed { index, def ->
            launch {
                runCatching { def.load() }
                    .onSuccess { items ->
                        val row = HomeRow(def.key, def.title, items.filter { it.posterPath != null })
                        _state.update { s ->
                            if (s.rows.getOrNull(index) == row) s
                            else s.copy(rows = s.rows.toMutableList().also { it[index] = row })
                        }
                    }
                    .onFailure { if (!fromCache) onError(it) }
            }
        }
    }

    private suspend fun loadHero(fromCache: Boolean) {
        val shown = _state.value.hero
        if (!fromCache && shown.isNotEmpty() && shown.all { it.logoPath != null }) return
        runCatching {
            val meta = graph.meta
            val trending = meta.trending().filter { it.backdropPath != null }.take(6)
            val genres = runCatching { meta.genreNames() }.getOrDefault(emptyMap())
            val withoutLogos = trending.map { item ->
                HeroItem(item, null, item.genres.ifEmpty { item.genreIds.mapNotNull(genres::get) }.take(2))
            }
            val logos = supervisorScope {
                trending.map { item -> async { runCatching { meta.logo(item.type, item.id) }.getOrNull() } }.awaitAll()
            }
            _state.update { s -> s.copy(hero = withoutLogos.mapIndexed { i, h -> h.copy(logoPath = logos[i]) }) }
        }.onFailure { if (!fromCache) onError(it) }
    }

    private fun onError(t: Throwable) {
        _state.update { if (it.error == null) it.copy(error = t.message ?: "Sin conexión") else it }
    }
}
