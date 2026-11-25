package com.synrg.harbor.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synrg.harbor.core.AppGraph
import com.synrg.harbor.data.HeroItem
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.tmdb.CacheOnly
import com.synrg.harbor.tmdb.MissingTmdbKeyException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
    val needsKey: Boolean = false,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    val continueWatching: StateFlow<List<ProgressEntry>> =
        graph.progress.continueWatching.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadJob: Job? = null

    private class RowDef(val key: String, val title: String, val load: suspend () -> List<MediaItem>)

    private val rowDefs: List<RowDef>
        get() {
            val tmdb = graph.tmdb
            return listOf(
                RowDef("trending", "Tendencias de la semana") { tmdb.trending().drop(6) },
                RowDef("movies", "Películas populares") { tmdb.list(MediaType.Movie, "popular") },
                RowDef("series", "Series populares") { tmdb.list(MediaType.Tv, "popular") },
                RowDef("cinema", "Ahora en cines") { tmdb.list(MediaType.Movie, "now_playing") },
                RowDef("anime", "Anime") {
                    tmdb.discover(
                        MediaType.Tv,
                        "with_genres" to "16",
                        "with_original_language" to "ja",
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to "50",
                    )
                },
                RowDef("top_movies", "Películas mejor valoradas") { tmdb.list(MediaType.Movie, "top_rated") },
                RowDef("top_series", "Series aclamadas") { tmdb.list(MediaType.Tv, "top_rated") },
                RowDef("airing", "Series en emisión") { tmdb.list(MediaType.Tv, "on_the_air") },
            )
        }

    init {
        load()
        viewModelScope.launch { graph.addons.seedDefaultsIfNeeded() }
        // Reload when the metadata language or the key changes in Settings.
        viewModelScope.launch {
            graph.settings.state.map { it.language to it.effectiveTmdbKey }.distinctUntilChanged().drop(1).collect { load() }
        }
    }

    /**
     * Two passes: first whatever the HTTP disk cache has (even stale) so the screen is full on
     * the first frames, then the network. Rows are replaced only if their content changed.
     */
    fun load() {
        loadJob?.cancel()
        val defs = rowDefs
        _state.value = HomeState(rows = List(defs.size) { null })
        loadJob = viewModelScope.launch {
            withContext(CacheOnly) { loadPass(defs, fromCache = true) }
            loadPass(defs, fromCache = false)
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadPass(defs: List<RowDef>, fromCache: Boolean) = supervisorScope {
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
            val tmdb = graph.tmdb
            val trending = tmdb.trending().filter { it.backdropPath != null }.take(6)
            val genres = runCatching { tmdb.genreNames() }.getOrDefault(emptyMap())
            val withoutLogos = trending.map { HeroItem(it, null, it.genreIds.mapNotNull(genres::get).take(2)) }
            val logos = supervisorScope {
                trending.map { item -> async { runCatching { tmdb.logo(item.type, item.id) }.getOrNull() } }.awaitAll()
            }
            _state.update { s -> s.copy(hero = withoutLogos.mapIndexed { i, h -> h.copy(logoPath = logos[i]) }) }
        }.onFailure { if (!fromCache) onError(it) }
    }

    private fun onError(t: Throwable) {
        _state.update {
            when (t) {
                is MissingTmdbKeyException -> it.copy(needsKey = true, error = null)
                else -> if (it.error == null) it.copy(error = t.message ?: "Sin conexión") else it
            }
        }
    }
}
