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
) {
    /** Can play right now: a link (a downloaded file only while it exists), or a torrent with the engine. */
    fun playable(torrents: Boolean): Boolean {
        val link = url ?: return infoHash != null && torrents
        return !link.startsWith("file://") || java.io.File(link.removePrefix("file://")).isFile
    }
}

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
@Immutable
@Serializable
data class TrackPref(
    val audio: String? = null,
    val subtitle: String? = null,
    /** The exact subtitle in use, so coming back to the same video brings back that same one. */
    val subtitleChoice: SubtitleChoice? = null,
    /** Subtitle sync set by hand; it belongs to one video (another file needs its own). */
    val subtitleDelay: SubtitleShift? = null,
    /** Language (ISO 639-1) of the second subtitle shown at the top; null when off. */
    val secondSubtitle: String? = null,
    /** The series' intro as learned from the user skipping it by hand (see player/IntroSkip.kt). */
    val intro: IntroSkip? = null,
)

/** An intro that starts around [startMs] and lasts [lengthMs]; the "Skip intro" button jumps [lengthMs]. */
@Immutable
@Serializable
data class IntroSkip(val startMs: Long, val lengthMs: Long)

@Immutable
@Serializable
data class SubtitleShift(val video: String, val ms: Long)

/**
 * A subtitle as picked: an internet one by its URL, or one of the file's tracks by language and
 * label (and position, which only means something for the same file). [video] is the progress key
 * of the movie or episode it was picked on.
 */
@Immutable
@Serializable
data class SubtitleChoice(
    val video: String,
    val externalUrl: String? = null,
    val externalId: String? = null,
    val language: String? = null,
    val label: String? = null,
    val index: Int? = null,
)

@Serializable
data class ProgressData(
    val entries: Map<String, ProgressEntry> = emptyMap(),
    val tracks: Map<String, TrackPref> = emptyMap(),
)

class ProgressRepository(private val store: JsonStore<ProgressData>) {

    /** One entry per title: the most recent unfinished thing, newest first. */
    val inProgress: Flow<List<ProgressEntry>> = store.state.map { data ->
        data.entries.values
            .filter { it.durationMs > 0 && it.fraction > 0.01f && !it.finished }
            .groupBy { it.type to it.tmdbId }
            .map { (_, list) -> list.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
    }.distinctUntilChanged()

    /** The home row: the newest 20 of [inProgress]. */
    val continueWatching: Flow<List<ProgressEntry>> = inProgress.map { it.take(20) }.distinctUntilChanged()

    fun forTitle(type: MediaType, id: Int): Flow<List<ProgressEntry>> = store.state.map { data ->
        data.entries.values.filter { it.type == type && it.tmdbId == id }
    }.distinctUntilChanged()

    fun get(key: String): ProgressEntry? = store.value.entries[key]

    val entries: Flow<Map<String, ProgressEntry>> = store.state.map { it.entries }.distinctUntilChanged()

    val all: Collection<ProgressEntry> get() = store.value.entries.values

    fun save(entry: ProgressEntry) = store.update { it.copy(entries = it.entries + (entry.key to entry)) }

    /**
     * Takes a title out of "Continue watching" without forgetting what was watched: unfinished entries go
     * back to the start (finished episodes keep their mark), like Stremio's "remove from continue watching".
     */
    fun dismiss(type: MediaType, id: Int) = store.update { data ->
        val now = System.currentTimeMillis()
        data.copy(
            entries = data.entries.mapValues { (_, e) ->
                if (e.type == type && e.tmdbId == id && !e.finished && e.positionMs > 0) e.copy(positionMs = 0, updatedAt = now) else e
            },
        )
    }

    fun remove(type: MediaType, id: Int) = store.update { data ->
        data.copy(entries = data.entries.filterValues { !(it.type == type && it.tmdbId == id) })
    }

    fun removeKey(key: String) = store.update { it.copy(entries = it.entries - key) }

    fun flush() = store.flushNow()

    /** Everything, for a backup. */
    val data: ProgressData get() = store.value

    /**
     * From a backup: entries the phone lacks, or has older, come in; per-title choices fill in the
     * titles that have none here. Returns how many entries changed.
     */
    fun merge(other: ProgressData): Int {
        var changed = 0
        store.update { data ->
            val entries = data.entries.toMutableMap()
            other.entries.forEach { (key, e) ->
                val mine = entries[key]
                if (mine == null || e.updatedAt > mine.updatedAt) {
                    entries[key] = e
                    changed++
                }
            }
            data.copy(entries = entries, tracks = other.tracks + data.tracks)
        }
        store.flushNow()
        return changed
    }

    fun trackPref(type: MediaType, id: Int): TrackPref? = store.value.tracks["${type.tmdb}:$id"]

    fun updateTrackPref(type: MediaType, id: Int, transform: (TrackPref) -> TrackPref) = store.update { data ->
        val key = "${type.tmdb}:$id"
        data.copy(tracks = data.tracks + (key to transform(data.tracks[key] ?: TrackPref())))
    }
}
