package com.synrg.harbor.episodes

import androidx.compose.runtime.Immutable
import com.synrg.harbor.core.JsonStore
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.ProgressRepository
import com.synrg.harbor.data.WatchlistRepository
import com.synrg.harbor.stremio.AddonRepository
import com.synrg.harbor.stremio.StremioSync
import com.synrg.harbor.tmdb.Metadata
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class CalendarEpisode(
    val tmdbId: Int,
    val imdbId: String,
    val show: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val season: Int,
    val episode: Int,
    val name: String? = null,
    val still: String? = null,
    /** When it comes out (epoch ms), as the metadata addon lists it. */
    val airsAt: Long,
) {
    val key: String get() = ProgressEntry.key(MediaType.Tv, tmdbId, season, episode)
}

@Serializable
data class CalendarData(
    /** Last background check: episodes out since then get a notification. 0 until the first one. */
    val lastCheck: Long = 0,
    val updatedAt: Long = 0,
    /** Out in the last week. */
    val recent: List<CalendarEpisode> = emptyList(),
    /** Coming in the next weeks. */
    val upcoming: List<CalendarEpisode> = emptyList(),
    /** Series (TMDB ids) whose new episodes should not notify. */
    val muted: Set<Int> = emptySet(),
)

/**
 * New and upcoming episodes of the series the user follows: the ones in "Mi lista" and the ones
 * watched lately. Dates come from the same metadata addon Stremio uses (usually Cinemeta), the
 * names in the app's language from TMDB.
 */
class EpisodeCalendar(
    private val store: JsonStore<CalendarData>,
    private val addons: AddonRepository,
    private val meta: Metadata,
    private val progress: ProgressRepository,
    private val watchlist: WatchlistRepository,
    private val sync: StremioSync,
) {
    val state: StateFlow<CalendarData> = store.state
    private val mutex = Mutex()

    private data class Show(val tmdbId: Int, val imdbId: String?, val title: String, val poster: String?, val backdrop: String?)

    private fun followed(): List<Show> {
        val since = System.currentTimeMillis() - FOLLOW_WATCHED_MS
        val shows = LinkedHashMap<Int, Show>()
        watchlist.entries.filter { it.item.type == MediaType.Tv }.forEach {
            shows[it.item.id] = Show(it.item.id, it.imdbId, it.item.title, it.item.posterPath, it.item.backdropPath)
        }
        progress.all.filter { it.type == MediaType.Tv && it.updatedAt > since }.sortedByDescending { it.updatedAt }.forEach { p ->
            val known = shows[p.tmdbId]
            if (known == null) shows[p.tmdbId] = Show(p.tmdbId, p.imdbId, p.title, p.poster, p.backdrop)
            else if (known.imdbId == null && p.imdbId != null) shows[p.tmdbId] = known.copy(imdbId = p.imdbId)
        }
        return shows.values.toList()
    }

    val followsAny: Boolean get() = followed().isNotEmpty()

    /** The first time alerts run: count from now, so nothing old shows up as new. */
    fun seed() {
        if (store.value.lastCheck == 0L) store.update { it.copy(lastCheck = System.currentTimeMillis()) }
    }

    fun setMuted(tmdbId: Int, imdbId: String?, muted: Boolean) {
        store.update { it.copy(muted = if (muted) it.muted + tmdbId else it.muted - tmdbId) }
        imdbId?.let { sync.setNotify(it, !muted) }
    }

    /** For the screen: refresh when the list is a few hours old. */
    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - store.value.updatedAt > STALE_MS) refresh(advance = false)
    }

    /**
     * Looks every followed series up again. With [advance] (the background check) it also moves
     * [CalendarData.lastCheck] and returns what came out since the previous check and is not watched.
     */
    suspend fun refresh(advance: Boolean): List<CalendarEpisode> = mutex.withLock {
        val now = System.currentTimeMillis()
        val gate = Semaphore(4)
        val found = coroutineScope {
            followed().map { show ->
                async { gate.withPermit { runCatching { episodesOf(show, now) }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten()
        }
        val before = store.value
        val fresh = if (advance && before.lastCheck > 0) {
            found.filter { it.airsAt > before.lastCheck && it.airsAt <= now && it.tmdbId !in before.muted && progress.get(it.key)?.finished != true }
        } else emptyList()
        store.update { data ->
            data.copy(
                recent = found.filter { it.airsAt in (now - RECENT_MS)..now }.sortedByDescending { it.airsAt },
                upcoming = found.filter { it.airsAt in (now + 1)..(now + UPCOMING_MS) }.sortedBy { it.airsAt },
                updatedAt = now,
                lastCheck = if (advance) now else data.lastCheck,
            )
        }
        fresh
    }

    private suspend fun episodesOf(show: Show, now: Long): List<CalendarEpisode> {
        val imdb = show.imdbId ?: meta.details(MediaType.Tv, show.tmdbId).imdbId ?: return emptyList()
        val window = addons.seriesVideos(imdb).filter { v ->
            val season = v.season ?: 0
            val released = v.released
            season > 0 && v.episode != null && released != null && released in (now - RECENT_MS)..(now + UPCOMING_MS)
        }
        if (window.isEmpty()) return emptyList()
        // Names and pictures in the app's language: one request per season involved.
        val bySeason = window.mapNotNull { it.season }.distinct().associateWith { s ->
            runCatching { meta.season(show.tmdbId, s) }.getOrDefault(emptyList()).associateBy { it.number }
        }
        return window.map { v ->
            val ep = bySeason[v.season]?.get(v.episode)
            CalendarEpisode(
                tmdbId = show.tmdbId, imdbId = imdb, show = show.title, poster = show.poster, backdrop = show.backdrop,
                season = v.season ?: 0, episode = v.episode ?: 0, name = ep?.name, still = ep?.stillPath, airsAt = v.released ?: now,
            )
        }
    }

    private companion object {
        const val DAY = 24 * 3600_000L
        const val FOLLOW_WATCHED_MS = 120 * DAY
        const val RECENT_MS = 7 * DAY
        const val UPCOMING_MS = 45 * DAY
        const val STALE_MS = 3 * 3600_000L
    }
}
