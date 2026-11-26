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
import com.synrg.harbor.downloads.DownloadItem
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.stremio.AddonStreams
import com.synrg.harbor.stremio.MarkTitle
import com.synrg.harbor.stremio.MarkVideo
import com.synrg.harbor.stremio.StreamOption
import com.synrg.harbor.stremio.StremioSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val initialSeason: Int? = null,
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

    /** What the Stremio account says was watched (null until it answers, or when signed out). */
    private val remoteWatched = MutableStateFlow<StremioSync.RemoteWatched?>(null)

    /** Progress keys that count as watched: finished here, or marked in the Stremio account. */
    val watched: StateFlow<Set<String>> = combine(progress, remoteWatched) { local, remote ->
        buildSet {
            local.values.filter { it.finished }.forEach { add(it.key) }
            if (remote?.movie == true) add(ProgressEntry.key(type, id))
            remote?.videos?.forEach { videoId ->
                val parts = videoId.split(':')
                val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
                val episode = parts.lastOrNull()?.toIntOrNull()
                if (parts.size >= 3 && season != null && episode != null) add(ProgressEntry.key(MediaType.Tv, id, season, episode))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** This title's downloads, by progress key. */
    val downloads: StateFlow<Map<String, DownloadItem>> = graph.downloads.items
        .map { d -> d.items.filter { it.type == type && it.tmdbId == id }.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun download(target: StreamTarget, saved: SavedStream) {
        val details = _state.value.details ?: return
        graph.downloads.add(
            DownloadItem(
                id = ProgressEntry.key(type, id, target.season, target.episode),
                type = type, tmdbId = id, imdbId = details.imdbId, season = target.season, episode = target.episode,
                title = details.item.title, episodeTitle = target.episodeTitle,
                poster = details.item.posterPath, backdrop = details.item.backdropPath, still = target.still,
                stream = saved,
            ),
        )
    }

    fun playDownloaded(item: DownloadItem): PlaybackRequest =
        item.playback(progress.value[item.id]?.takeIf { !it.finished && it.positionMs > 30_000 }?.positionMs ?: 0L)

    val inWatchlist: StateFlow<Boolean> = graph.watchlist.contains(type, id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** For a followed series (in the list, or watched): whether its new episodes notify. Null otherwise. */
    val alerts: StateFlow<Boolean?> = combine(graph.episodes.state, inWatchlist, progress) { calendar, inList, watched ->
        if (type == MediaType.Tv && (inList || watched.isNotEmpty())) id !in calendar.muted else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleAlerts() {
        val on = alerts.value ?: return
        graph.episodes.setMuted(id, _state.value.details?.imdbId, muted = on)
    }

    private val _playRequests = Channel<PlaybackRequest>(Channel.BUFFERED)
    /** Playback that starts without the sources list (resuming with the source used last time). */
    val playRequests: Flow<PlaybackRequest> = _playRequests.receiveAsFlow()

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
                    loadRemoteWatched(details)
                    if (type == MediaType.Tv) {
                        val latest = progress.value.values.maxByOrNull { it.updatedAt }
                        val season = initialSeason?.takeIf { s -> details.seasons.any { it.number == s } }
                            ?: latest?.season?.takeIf { s -> details.seasons.any { it.number == s } }
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

    private fun loadRemoteWatched(details: Details) {
        val imdb = details.imdbId ?: return
        viewModelScope.launch {
            val remote = runCatching { graph.sync.remoteWatched(type, imdb) }.getOrNull() ?: return@launch
            remoteWatched.value = remote
            // Finished here before marks reached the account (or while signed out): fill them in there.
            val finished = progress.value.values.filter { it.finished }
            val missing = if (type == MediaType.Movie) {
                listOf(imdb).takeIf { finished.isNotEmpty() && !remote.movie }.orEmpty()
            } else {
                finished.filter { it.season != null && it.episode != null }.map { "$imdb:${it.season}:${it.episode}" }.filter { it !in remote.videos }
            }
            if (missing.isNotEmpty()) {
                graph.sync.setWatched(type, imdb, details.item.title, details.item.posterPath, missing, watched = true, advance = false)
            }
        }
    }

    private fun markTitle(details: Details) = MarkTitle(
        type = type, tmdbId = id, imdbId = details.imdbId, title = details.item.title,
        poster = details.item.posterPath, backdrop = details.item.backdropPath, runtimeMin = details.runtime,
    )

    /** "Visto" on episodes (one, or a whole season): here and in the Stremio account. */
    fun setEpisodesWatched(episodes: List<Episode>, watched: Boolean) {
        val details = _state.value.details ?: return
        val targets = episodes.filter { it.released }
        graph.marks.set(markTitle(details), targets.map { MarkVideo(it.season, it.number, it.name, it.stillPath, it.runtime) }, watched)
        val imdb = details.imdbId ?: return
        val ids = targets.map { "$imdb:${it.season}:${it.number}" }.toSet()
        remoteWatched.update { r -> r?.copy(videos = if (watched) r.videos + ids else r.videos - ids) }
    }

    fun setMovieWatched(watched: Boolean) {
        val details = _state.value.details ?: return
        graph.marks.set(markTitle(details), listOf(MarkVideo()), watched)
        remoteWatched.update { it?.copy(movie = watched) }
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

    /**
     * The big button: the movie, or the episode you are on / the next one. Something half watched
     * resumes right away with the source it was playing; otherwise the sources list opens.
     */
    fun playMain() {
        if (_state.value.partial) {
            autoplay = true // runs as soon as the full details arrive
            return
        }
        viewModelScope.launch {
            val target = mainTarget() ?: return@launch
            val key = ProgressEntry.key(type, id, target.season, target.episode)
            val entry = progress.value[key]
            // Downloaded: straight to the file, no internet needed.
            graph.downloads.local(key)?.let { local ->
                _playRequests.send(local.playback(entry?.takeIf { !it.finished && it.positionMs > 30_000 }?.positionMs ?: 0L))
                return@launch
            }
            val saved = entry?.lastStream?.takeIf { !entry.finished && entry.positionMs > 0 && playable(it) }
            val request = saved?.let { playbackRequest(target, it) }
            if (request != null) _playRequests.send(request) else openStreams(target)
        }
    }

    /** "Fuentes": the sources list for what the big button would play, to switch to another one. */
    fun chooseSource() {
        viewModelScope.launch { mainTarget()?.let(::openStreams) }
    }

    /** True when the big button resumes directly, so the screen also offers "Fuentes". */
    fun resumesDirectly(progress: Map<String, ProgressEntry>): Boolean {
        val latest = progress.values.maxByOrNull { it.updatedAt } ?: return false
        val saved = latest.lastStream ?: return false
        return !latest.finished && latest.positionMs > 0 && playable(saved)
    }

    private fun playable(saved: SavedStream) = saved.playable(graph.torrents.available)

    private suspend fun mainTarget(): StreamTarget? {
        if (type == MediaType.Movie) return StreamTarget()
        val details = _state.value.details ?: return null
        val latest = progress.value.values.maxByOrNull { it.updatedAt }
        return run {
            if (latest?.season != null && latest.episode != null) {
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
