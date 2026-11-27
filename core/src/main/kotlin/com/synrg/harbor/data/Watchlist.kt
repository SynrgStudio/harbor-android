package com.synrg.harbor.data

import androidx.compose.runtime.Immutable
import com.synrg.harbor.core.JsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** [imdbId] links the entry to the Stremio library, which keys titles by IMDb id. */
@Immutable
@Serializable
data class WatchlistEntry(val item: MediaItem, val addedAt: Long, val imdbId: String? = null)

@Serializable
data class WatchlistData(val entries: List<WatchlistEntry> = emptyList())

class WatchlistRepository(private val store: JsonStore<WatchlistData>) {
    val items: Flow<List<MediaItem>> = store.state.map { d -> d.entries.sortedByDescending { it.addedAt }.map { it.item } }
        .distinctUntilChanged()

    val entries: List<WatchlistEntry> get() = store.value.entries

    fun contains(type: MediaType, id: Int): Flow<Boolean> =
        store.state.map { d -> d.entries.any { it.item.type == type && it.item.id == id } }.distinctUntilChanged()

    /** Adds or removes the title; returns true if it is in the list afterwards. */
    fun toggle(item: MediaItem, imdbId: String?): Boolean {
        var added = false
        store.update { d ->
            val exists = d.entries.any { it.item.type == item.type && it.item.id == item.id }
            added = !exists
            if (exists) d.copy(entries = d.entries.filterNot { it.item.type == item.type && it.item.id == item.id })
            else d.copy(entries = d.entries + WatchlistEntry(item, System.currentTimeMillis(), imdbId))
        }
        return added
    }

    fun add(entry: WatchlistEntry) = store.update { d ->
        if (d.entries.any { it.item.type == entry.item.type && it.item.id == entry.item.id }) d
        else d.copy(entries = d.entries + entry)
    }

    fun remove(type: MediaType, id: Int) = store.update { d ->
        d.copy(entries = d.entries.filterNot { it.item.type == type && it.item.id == id })
    }

    fun setImdbId(type: MediaType, id: Int, imdbId: String) = store.update { d ->
        d.copy(entries = d.entries.map { if (it.item.type == type && it.item.id == id) it.copy(imdbId = imdbId) else it })
    }
}
