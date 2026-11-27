package com.synrg.harbor.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A tiny persisted state holder: the whole value lives in memory as a StateFlow and is written
 * back as JSON (atomically, debounced). Plenty for settings, progress and the watchlist, and it
 * avoids a database and annotation processing.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state
    val value: T get() = _state.value

    private var pending: Job? = null
    private val writeLock = Any()

    private fun load(): T? = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
    }.getOrNull()

    fun update(transform: (T) -> T) {
        _state.update(transform)
        schedule()
    }

    @Synchronized
    private fun schedule() {
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(300)
            write()
        }
    }

    /** Writes right away (used when the player goes to the background). */
    fun flushNow() {
        scope.launch(Dispatchers.IO) { write() }
    }

    private fun write() = synchronized(writeLock) {
        runCatching {
            val text = json.encodeToString(serializer, _state.value)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
