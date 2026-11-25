package com.synrg.harbor.data

import androidx.compose.runtime.Immutable
import com.synrg.harbor.core.JsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** What is needed to play a source again (a torrent hash keeps working; debrid URLs may expire). */
@Immutable
@Serializable
data class SavedStream(
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val trackers: List<String> = emptyList(),
    val filename: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val addonName: String? = null,
    val label: String? = null,
    val bingeGroup: String? = null,
)

@Immutable
@Serializable
data class ProgressEntry(
    val key: String,
    val type: MediaType,
    val tmdbId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val still: String? = null,
    val imdbId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    val lastStream: SavedStream? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && fraction >= FINISHED
    val remainingMin: Int get() = ((durationMs - positionMs) / 60_000).toInt().coerceAtLeast(0)

    companion object {
        const val FINISHED = 0.92f
        fun key(type: MediaType, id: Int, season: Int? = null, episode: Int? = null) =
            if (type == MediaType.Tv && season != null && episode != null) "tv:$id:$season:$episode" else "${type.tmdb}:$id"
    }
}

/**
 * Audio/subtitle choice remembered per title. Languages are ISO 639-1; [subtitle] "" means off.
 * Null fields fall back to the global setting.
 */
@Serializable
data class TrackPref(val audio: String? = null, val subtitle: String? = null)

@Serializable
data class ProgressData(
    val entries: Map<String, ProgressEntry> = emptyMap(),
    val tracks: Map<String, TrackPref> = emptyMap(),
)

class ProgressRepository(private val store: JsonStore<ProgressData>) {

    /** One card per title: the most recent unfinished thing, newest first. */
    val continueWatching: Flow<List<ProgressEntry>> = store.state.map { data ->
        data.entries.values
            .filter { it.durationMs > 0 && it.fraction > 0.01f && !it.finished }
            .groupBy { it.type to it.tmdbId }
            .map { (_, list) -> list.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
            .take(20)
    }.distinctUntilChanged()

    fun forTitle(type: MediaType, id: Int): Flow<List<ProgressEntry>> = store.state.map { data ->
        data.entries.values.filter { it.type == type && it.tmdbId == id }
    }.distinctUntilChanged()

    fun get(key: String): ProgressEntry? = store.value.entries[key]

    fun save(entry: ProgressEntry) = store.update { it.copy(entries = it.entries + (entry.key to entry)) }

    fun remove(type: MediaType, id: Int) = store.update { data ->
        data.copy(entries = data.entries.filterValues { !(it.type == type && it.tmdbId == id) })
    }

    fun flush() = store.flushNow()

    fun trackPref(type: MediaType, id: Int): TrackPref? = store.value.tracks["${type.tmdb}:$id"]

    fun updateTrackPref(type: MediaType, id: Int, transform: (TrackPref) -> TrackPref) = store.update { data ->
        val key = "${type.tmdb}:$id"
        data.copy(tracks = data.tracks + (key to transform(data.tracks[key] ?: TrackPref())))
    }
}
