package com.synrg.harbor.cast

import kotlinx.coroutines.flow.StateFlow

/**
 * Chromecasts and Google TVs, where the platform can reach them (on Android, Google's Cast
 * library). [CastManager] lists them next to the DLNA TVs.
 */
interface Chromecasts {
    class Route(val id: String, val name: String)

    class Status(val positionMs: Long, val durationMs: Long, val playing: Boolean, val loading: Boolean, val failed: Boolean)

    /** The Chromecasts found while discovering. */
    val routes: StateFlow<List<Route>>

    suspend fun startDiscovery()

    suspend fun stopDiscovery()

    /** Connects to the Chromecast and waits for the session. */
    suspend fun connect(routeId: String)

    suspend fun load(url: String, mime: String, title: String, subtitle: String?, image: String?, startMs: Long, vttUrl: String?, vttLanguage: String?)

    /** Position, duration, playing and loading, as the Chromecast last reported them. */
    suspend fun status(): Status?

    suspend fun play()

    suspend fun pause()

    suspend fun seek(ms: Long)

    suspend fun end()
}
