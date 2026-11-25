package com.synrg.harbor.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synrg.harbor.core.AppGraph
import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.SavedStream
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.stremio.AddonStreams
import com.synrg.harbor.stremio.StreamOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class StreamTarget(
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val still: String? = null,
)

@Immutable
data class StreamsUi(
    val target: StreamTarget,
    val groups: List<AddonStreams>,
    val resume: ProgressEntry?,
    val error: String? = null,
)

@Immutable
data class DetailState(
    val details: Details? = null,
    /** [details] only holds what the list knew (title, images, overview); TMDB has not answered yet. */
    val partial: Boolean = false,
    val error: String? = null,
    val season: Int? = null,
    val episodes: List<Episode>? = null,
    val episodesLoading: Boolean = false,
)

class DetailViewModel(
    private val graph: AppGraph,
    val type: MediaType,
    val id: Int,
    private var autoplay: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(
        graph.previews["${type.tmdb}:$id"]?.let { preview ->
            DetailState(
                details = Details(
                    item = preview, tagline = null, genres = emptyList(), runtime = null, seasons = emptyList(),
                    cast = emptyList(), recommendations = emptyList(), imdbId = null, logoPath = null,
                    status = null, certification = null,
                ),
                partial = true,
            )
        } ?: DetailState(),
    )
    val state: StateFlow<DetailState> = _state

    private val _streams = MutableStateFlow<StreamsUi?>(null)
    val streams: StateFlow<StreamsUi?> = _streams

    val progress: StateFlow<Map<String, ProgressEntry>> = graph.progress.forTitle(type, id)
        .map { list -> list.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val inWatchlist: StateFlow<Boolean> = graph.watchlist.contains(type, id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var streamsJob: Job? = null
    private var seasonJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            runCatching { graph.meta.details(type, id) }
                .onSuccess { details ->
                    _state.update { it.copy(details = details, partial = false) }
                    if (details.recommendations.isEmpty()) loadRecommendations(details)
                    if (type == MediaType.Tv) {
                        val latest = progress.value.values.maxByOrNull { it.updatedAt }
                        val season = latest?.season?.takeIf { s -> details.seasons.any { it.number == s } }
                            ?: details.seasons.firstOrNull()?.number
                        if (season != null) selectSeason(season)
                    }
                    if (autoplay) {
                        autoplay = false
                        playMain()
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Sin conexión", details = null, partial = false) } }
        }
    }

    /** Filled after the page is up: it is the last row, no reason to hold the rest for it. */
    private fun loadRecommendations(details: Details) = viewModelScope.launch {
        val recs = runCatching { graph.meta.recommendations(details) }.getOrNull().orEmpty()
        if (recs.isNotEmpty()) {
            _state.update { s -> s.copy(details = s.details?.copy(recommendations = recs)) }
        }
    }

    fun selectSeason(number: Int) {
        if (_state.value.season == number && _state.value.episodes != null) return
        seasonJob?.cancel()
        _state.update { it.copy(season = number, episodes = null, episodesLoading = true) }
        seasonJob = viewModelScope.launch {
            val episodes = runCatching { graph.meta.season(id, number) }.getOrNull()
            _state.update { it.copy(episodes = episodes ?: emptyList(), episodesLoading = false) }
        }
    }

    fun toggleWatchlist() {
        val details = _state.value.details ?: return
        val added = graph.watchlist.toggle(details.item, details.imdbId)
        graph.sync.watchlistChanged(details.item, details.imdbId, added)
    }

    /** The big button: the movie, or the episode you are on / the next one. */
    fun playMain() {
        if (_state.value.partial) {
            autoplay = true // runs as soon as the full details arrive
            return
        }
        if (type == MediaType.Movie) {
            openStreams(StreamTarget())
            return
        }
        viewModelScope.launch {
            val details = _state.value.details ?: return@launch
            val latest = progress.value.values.maxByOrNull { it.updatedAt }
            val target = if (latest?.season != null && latest.episode != null) {
                if (!latest.finished) {
                    episodeTarget(latest.season, latest.episode)
                } else {
                    val eps = runCatching { graph.meta.season(id, latest.season) }.getOrDefault(emptyList())
                    val next = eps.firstOrNull { it.number == latest.episode + 1 }
                    when {
                        next != null -> StreamTarget(next.season, next.number, next.name, next.stillPath)
                        else -> details.seasons.firstOrNull { it.number > latest.season }?.let { episodeTarget(it.number, 1) }
                            ?: episodeTarget(latest.season, latest.episode)
                    }
                }
            } else {
                details.seasons.firstOrNull()?.let { episodeTarget(it.number, 1) }
            }
            if (target != null) openStreams(target)
        }
    }

    private suspend fun episodeTarget(season: Int, episode: Int): StreamTarget {
        val ep = runCatching { graph.meta.season(id, season) }.getOrNull()?.firstOrNull { it.number == episode }
        return StreamTarget(season, episode, ep?.name, ep?.stillPath)
    }

    fun openEpisode(ep: Episode) = openStreams(StreamTarget(ep.season, ep.number, ep.name, ep.stillPath))

    fun mainButtonLabel(progress: Map<String, ProgressEntry>): String {
        val latest = progress.values.maxByOrNull { it.updatedAt } ?: return "Ver ahora"
        return when {
            type == MediaType.Movie && !latest.finished -> "Continuar · quedan ${latest.remainingMin} min"
            type == MediaType.Movie -> "Ver de nuevo"
            latest.season != null && !latest.finished -> "Continuar T${latest.season}:E${latest.episode}"
            latest.season != null -> "Siguiente episodio"
            else -> "Ver ahora"
        }
    }

    fun openStreams(target: StreamTarget) {
        val details = _state.value.details ?: return
        streamsJob?.cancel()
        val key = ProgressEntry.key(type, id, target.season, target.episode)
        val resume = progress.value[key]
        val imdb = details.imdbId
        if (imdb == null) {
            _streams.value = StreamsUi(target, emptyList(), resume, error = "Este título no tiene ID de IMDb, así que los addons no pueden buscarlo.")
            return
        }
        _streams.value = StreamsUi(target, emptyList(), resume)
        val stremioId = if (target.season != null) "$imdb:${target.season}:${target.episode}" else imdb
        streamsJob = viewModelScope.launch {
            graph.addons.streams(type.stremio, stremioId).collect { update ->
                _streams.update { ui ->
                    ui ?: return@update null
                    val groups = ui.groups.toMutableList()
                    val i = groups.indexOfFirst { it.addon.transportUrl == update.addon.transportUrl }
                    if (i >= 0) groups[i] = update else groups += update
                    ui.copy(groups = groups)
                }
            }
            _streams.update { ui -> if (ui != null && ui.groups.isEmpty()) ui.copy(error = "No tenés addons de fuentes activos. Agregalos en Ajustes.") else ui }
        }
    }

    fun closeStreams() {
        streamsJob?.cancel()
        _streams.value = null
    }

    fun playbackRequest(target: StreamTarget, stream: SavedStream): PlaybackRequest? {
        val details = _state.value.details ?: return null
        val key = ProgressEntry.key(type, id, target.season, target.episode)
        val previous = progress.value[key]
        val start = previous?.takeIf { !it.finished && it.positionMs > 30_000 }?.positionMs ?: 0L
        return PlaybackRequest(
            type = type,
            tmdbId = id,
            imdbId = details.imdbId,
            season = target.season,
            episode = target.episode,
            title = details.item.title,
            episodeTitle = target.episodeTitle,
            poster = details.item.posterPath,
            backdrop = details.item.backdropPath,
            still = target.still,
            stream = stream,
            startPositionMs = start,
        )
    }

    fun requestFor(option: StreamOption, target: StreamTarget) = playbackRequest(target, option.saved)
}
