package com.synrg.harbor.data

import com.synrg.harbor.core.JsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class WatchlistEntry(val item: MediaItem, val addedAt: Long)

@Serializable
data class WatchlistData(val entries: List<WatchlistEntry> = emptyList())

class WatchlistRepository(private val store: JsonStore<WatchlistData>) {
    val items: Flow<List<MediaItem>> = store.state.map { d -> d.entries.sortedByDescending { it.addedAt }.map { it.item } }
        .distinctUntilChanged()

    fun contains(type: MediaType, id: Int): Flow<Boolean> =
        store.state.map { d -> d.entries.any { it.item.type == type && it.item.id == id } }.distinctUntilChanged()

    fun toggle(item: MediaItem) = store.update { d ->
        val exists = d.entries.any { it.item.type == item.type && it.item.id == item.id }
        if (exists) d.copy(entries = d.entries.filterNot { it.item.type == item.type && it.item.id == item.id })
        else d.copy(entries = d.entries + WatchlistEntry(item, System.currentTimeMillis()))
    }
}
