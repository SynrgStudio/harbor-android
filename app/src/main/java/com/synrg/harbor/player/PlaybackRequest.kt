package com.synrg.harbor.player

import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.SavedStream
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackRequest(
    val type: MediaType,
    val tmdbId: Int,
    val imdbId: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val still: String? = null,
    val stream: SavedStream,
    val startPositionMs: Long = 0,
) {
    val progressKey: String get() = ProgressEntry.key(type, tmdbId, season, episode)
    val stremioType: String get() = type.stremio
    val stremioId: String? get() = imdbId?.let { if (season != null && episode != null) "$it:$season:$episode" else it }
    /** A downloaded file: plays without internet. */
    val isLocal: Boolean get() = stream.url?.startsWith("file:") == true
    companion object {
        /** Plays [entry] again with the source it was last played with, from where it was left. */
        fun resume(entry: ProgressEntry, stream: SavedStream) = PlaybackRequest(
            type = entry.type,
            tmdbId = entry.tmdbId,
            imdbId = entry.imdbId,
            season = entry.season,
            episode = entry.episode,
            title = entry.title,
            episodeTitle = entry.episodeTitle,
            poster = entry.poster,
            backdrop = entry.backdrop,
            still = entry.still,
            stream = stream,
            startPositionMs = entry.positionMs.takeIf { !entry.finished && it > 30_000 } ?: 0L,
        )
    }

    val displaySubtitle: String?
        get() = if (season != null && episode != null) "T$season · E$episode" + (episodeTitle?.let { " — $it" } ?: "") else null
}
