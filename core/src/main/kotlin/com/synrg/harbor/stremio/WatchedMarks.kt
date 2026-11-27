package com.synrg.harbor.stremio

import androidx.compose.runtime.Immutable
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.ProgressRepository

/** A title as marking it watched needs it. */
@Immutable
data class MarkTitle(
    val type: MediaType,
    val tmdbId: Int,
    val imdbId: String?,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val runtimeMin: Int? = null,
)

/** The movie itself (no season/episode) or one episode. */
@Immutable
data class MarkVideo(
    val season: Int? = null,
    val episode: Int? = null,
    val name: String? = null,
    val still: String? = null,
    val runtimeMin: Int? = null,
)

/**
 * "Watched" by hand: here it is a progress entry at the end (or none), in the Stremio account it is
 * the same mark Stremio and Harbor desktop show.
 */
class WatchedMarks(private val progress: ProgressRepository, private val sync: StremioSync) {
    fun set(title: MarkTitle, videos: List<MarkVideo>, watched: Boolean) {
        if (videos.isEmpty()) return
        val now = System.currentTimeMillis()
        videos.forEachIndexed { i, v ->
            val key = ProgressEntry.key(title.type, title.tmdbId, v.season, v.episode)
            val existing = progress.get(key)
            if (watched) {
                val minutes = (v.runtimeMin ?: title.runtimeMin)?.takeIf { it > 0 } ?: 1
                val duration = existing?.durationMs?.takeIf { it > 0 } ?: (minutes * 60_000L)
                val base = existing ?: ProgressEntry(
                    key = key, type = title.type, tmdbId = title.tmdbId, season = v.season, episode = v.episode,
                    title = title.title, episodeTitle = v.name, poster = title.poster, backdrop = title.backdrop,
                    still = v.still, imdbId = title.imdbId,
                )
                // In order, so the last one marked counts as the latest ("Next episode" follows it).
                progress.save(base.copy(positionMs = duration, durationMs = duration, updatedAt = now + i, imdbId = base.imdbId ?: title.imdbId))
            } else if (existing?.finished == true) {
                progress.removeKey(key)
            }
        }
        progress.flush()
        val imdb = title.imdbId ?: return
        val ids = videos.map { v -> if (v.season != null && v.episode != null) "$imdb:${v.season}:${v.episode}" else imdb }
        sync.setWatched(title.type, imdb, title.title, title.poster, ids, watched)
    }
}
