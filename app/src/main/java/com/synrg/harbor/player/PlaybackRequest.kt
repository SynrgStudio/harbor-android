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
    val displaySubtitle: String?
        get() = if (season != null && episode != null) "T$season · E$episode" + (episodeTitle?.let { " — $it" } ?: "") else null
}
