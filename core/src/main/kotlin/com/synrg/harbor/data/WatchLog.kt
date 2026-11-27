package com.synrg.harbor.data

import androidx.compose.runtime.Immutable
import com.synrg.harbor.core.JsonStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Time spent on one title, for the stats' "most watched". */
@Immutable
@Serializable
data class TitleTime(
    val type: MediaType,
    val tmdbId: Int,
    val title: String,
    val poster: String? = null,
    val seconds: Long = 0,
    val lastAt: Long = 0,
)

/**
 * How long the player actually played: seconds per day (ISO date) and per title. Kept by the
 * player while it plays; the stats screen reads it. It starts empty in 0.8.0: older viewing only
 * counts in the finished movies and episodes, which come from progress.
 */
@Serializable
data class WatchLog(
    val days: Map<String, Long> = emptyMap(),
    val titles: Map<String, TitleTime> = emptyMap(),
)

class WatchLogRepository(private val store: JsonStore<WatchLog>) {
    val state: StateFlow<WatchLog> = store.state
    val value: WatchLog get() = store.value

    fun add(type: MediaType, tmdbId: Int, title: String, poster: String?, seconds: Long) {
        val day = LocalDate.now().toString()
        val key = "${type.tmdb}:$tmdbId"
        store.update { log ->
            val previous = log.titles[key] ?: TitleTime(type, tmdbId, title, poster)
            log.copy(
                days = log.days + (day to (log.days[day] ?: 0L) + seconds),
                titles = log.titles + (key to previous.copy(title = title, poster = poster ?: previous.poster, seconds = previous.seconds + seconds, lastAt = System.currentTimeMillis())),
            )
        }
    }

    /** From a backup: the larger count wins, so restoring onto the same phone doesn't double anything. */
    fun merge(other: WatchLog) = store.update { log ->
        log.copy(
            days = (log.days.keys + other.days.keys).associateWith { maxOf(log.days[it] ?: 0L, other.days[it] ?: 0L) },
            titles = (log.titles.keys + other.titles.keys).associateWith { k ->
                val a = log.titles[k]
                val b = other.titles[k]
                if (a == null) b!! else if (b == null || a.seconds >= b.seconds) a else b
            },
        )
    }
}
