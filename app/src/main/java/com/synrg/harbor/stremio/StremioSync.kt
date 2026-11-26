package com.synrg.harbor.stremio

import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.ProgressRepository
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.data.WatchlistEntry
import com.synrg.harbor.data.WatchlistRepository
import com.synrg.harbor.tmdb.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps "Seguir viendo" and "Mi lista" in step with the Stremio account's library, the same one
 * Stremio and Harbor desktop use. Stremio keys titles by IMDb id and keeps one playback state per
 * title (the video in progress and its offset); a library item that is not "removed" is in the list,
 * and a "temp" one exists only because it was watched.
 */
class StremioSync(
    private val account: StremioAccount,
    private val addons: AddonRepository,
    private val settings: SettingsRepository,
    private val progress: ProgressRepository,
    private val watchlist: WatchlistRepository,
    private val meta: Metadata,
    private val scope: CoroutineScope,
) {
    data class Result(val continueWatching: Int, val added: Int)

    /** What the account says was watched: a movie, or a series' video ids ("tt…:season:episode"). */
    data class RemoteWatched(val movie: Boolean, val videos: Set<String>)

    private val mutex = Mutex()
    /** Watched marks read, change and write a whole library item: one at a time. */
    private val marksMutex = Mutex()
    private val pendingProgress = ConcurrentHashMap<String, Job>()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    @Volatile private var lastPull = 0L

    private val signedIn: Boolean get() = settings.current.stremioAuthKey != null

    /** On app start or return: at most once a minute, errors stay quiet (offline is normal). */
    fun pullInBackground() {
        if (!signedIn || System.currentTimeMillis() - lastPull < 60_000) return
        lastPull = System.currentTimeMillis()
        scope.launch { runCatching { pull() } }
    }

    suspend fun pull(): Result = mutex.withLock {
        _syncing.value = true
        try {
            lastPull = System.currentTimeMillis()
            doPull()
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun doPull(): Result = coroutineScope {
        val remote = account.libraryGet().mapNotNull(RemoteItem::parse)
        val byKey = remote.associateBy { it.key }

        // Titles already known here need no lookup: their IMDb id is saved with them.
        val known = ConcurrentHashMap<String, MediaItem>()
        watchlist.entries.forEach { e -> e.imdbId?.let { known["${e.item.type.stremio}:$it"] = e.item } }
        progress.all.forEach { p ->
            p.imdbId?.let { known.putIfAbsent("${p.type.stremio}:$it", MediaItem(p.tmdbId, p.type, p.title, p.poster, p.backdrop)) }
        }
        val gate = Semaphore(6)
        suspend fun resolve(r: RemoteItem): MediaItem? = known[r.key]
            ?: gate.withPermit { runCatching { meta.itemByStremioId(r.type, r.id) }.getOrNull() }?.also { known[r.key] = it }

        // 1. Continue watching: the newest titles in progress on any device.
        val merged = AtomicInteger()
        remote.filter { it.inContinueWatching }.sortedByDescending { it.watchedAt }.take(MAX_CONTINUE)
            .map { r -> async { resolve(r)?.let { if (mergeProgress(r, it)) merged.incrementAndGet() } } }
            .awaitAll()

        // 2. Rewound elsewhere (finished, or removed from "continue watching"): drop it here too.
        // Only for items that were played somewhere (video_id set): adding a title to the library
        // later also bumps its time, and that must not wipe what was watched here.
        progress.all.filter { it.imdbId != null && it.positionMs > 0 && !it.finished }
            .groupBy { "${it.type.stremio}:${it.imdbId}" }
            .forEach { (key, entries) ->
                val r = byKey[key] ?: return@forEach
                if (r.timeOffset == 0L && r.videoId != null && r.mtime > entries.maxOf { it.updatedAt } + SKEW_MS) {
                    entries.forEach { progress.save(it.copy(positionMs = 0, updatedAt = r.mtime)) }
                }
            }

        // 3. The list: bring in what was added elsewhere, drop what was removed later than we added it.
        val local = watchlist.entries
        val localByKey = local.filter { it.imdbId != null }.associateBy { "${it.item.type.stremio}:${it.imdbId}" }
        val added = AtomicInteger()
        remote.filter { it.inLibrary && it.key !in localByKey }
            .map { r ->
                async {
                    val item = resolve(r) ?: return@async
                    val imdb = r.id.takeIf { it.startsWith("tt") }
                    if (local.none { it.item.type == item.type && it.item.id == item.id }) {
                        watchlist.add(WatchlistEntry(item, r.ctime ?: r.mtime, imdb))
                        added.incrementAndGet()
                    } else if (imdb != null) {
                        watchlist.setImdbId(item.type, item.id, imdb)
                    }
                }
            }.awaitAll()
        remote.filter { it.removed && !it.temp }.forEach { r ->
            val entry = localByKey[r.key] ?: return@forEach
            if (r.mtime > entry.addedAt + SKEW_MS) watchlist.remove(entry.item.type, entry.item.id)
        }

        // 4. Ours that the account does not have yet (added here while signed out, or before linking).
        val toPush = watchlist.entries.mapNotNull { e ->
            val imdb = e.imdbId ?: imdbFor(e.item)?.also { watchlist.setImdbId(e.item.type, e.item.id, it) } ?: return@mapNotNull null
            val r = byKey["${e.item.type.stremio}:$imdb"]
            if (r != null && (r.inLibrary || r.mtime > e.addedAt)) return@mapNotNull null
            libraryEntry(r?.raw, imdb, e.item.type, e.item.title, e.item.posterPath, inLibrary = true)
        }
        account.libraryPut(toPush)

        Result(merged.get(), added.get())
    }

    /** Saves the remote state of [r] if it is newer than ours; true if something changed. */
    private suspend fun mergeProgress(r: RemoteItem, item: MediaItem): Boolean {
        if (r.duration <= 0) return false
        val (season, episode) = r.episode()
        if (item.type == MediaType.Tv && (season == null || episode == null)) return false
        val key = ProgressEntry.key(item.type, item.id, season, episode)
        val local = progress.get(key)
        if (local != null && local.updatedAt >= r.watchedAt - SKEW_MS) return false
        val ep = if (season != null && local?.episodeTitle == null) {
            runCatching { meta.season(item.id, season) }.getOrNull()?.firstOrNull { it.number == episode }
        } else null
        val base = local ?: ProgressEntry(
            key = key, type = item.type, tmdbId = item.id, season = season, episode = episode,
            title = item.title, poster = item.posterPath, backdrop = item.backdropPath,
        )
        progress.save(
            base.copy(
                episodeTitle = base.episodeTitle ?: ep?.name,
                still = base.still ?: ep?.stillPath,
                imdbId = base.imdbId ?: r.id.takeIf { it.startsWith("tt") },
                positionMs = r.timeOffset,
                durationMs = r.duration,
                updatedAt = r.watchedAt,
            ),
        )
        return true
    }

    /** After the player saves (pause, exit, end): send it, collapsing bursts into one write. */
    fun progressChanged(entry: ProgressEntry) {
        if (!signedIn) return
        val imdb = entry.imdbId ?: return
        val key = "${entry.type.stremio}:$imdb"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(1_500)
            runCatching { pushProgress(entry, imdb) }
            coroutineContext[Job]?.let { pendingProgress.remove(key, it) }
        }
        pendingProgress.put(key, job)?.cancel()
        job.start()
    }

    /** Removed from "Seguir viendo": back to 0 in the account too, which is what Stremio does. */
    fun dismissed(entry: ProgressEntry) {
        if (!signedIn) return
        val imdb = entry.imdbId ?: return
        pendingProgress.remove("${entry.type.stremio}:$imdb")?.cancel()
        scope.launch {
            runCatching {
                val existing = account.libraryGet(listOf(imdb)).firstOrNull() ?: return@runCatching
                val state = existing["state"] as? JsonObject ?: return@runCatching
                val now = JsonPrimitive(iso(System.currentTimeMillis()))
                val rewound = JsonObject(state + ("timeOffset" to JsonPrimitive(0)))
                account.libraryPut(listOf(JsonObject(existing + mapOf("state" to rewound, "_mtime" to now))))
            }
        }
    }

    fun watchlistChanged(item: MediaItem, imdbId: String?, added: Boolean) {
        if (!signedIn) return
        scope.launch {
            runCatching {
                val imdb = imdbId ?: imdbFor(item) ?: return@runCatching
                val existing = account.libraryGet(listOf(imdb)).firstOrNull()
                if (!added && existing == null) return@runCatching
                account.libraryPut(listOf(libraryEntry(existing, imdb, item.type, item.title, item.posterPath, inLibrary = added)))
            }
        }
    }

    suspend fun remoteWatched(type: MediaType, imdb: String): RemoteWatched? {
        if (!signedIn) return null
        val state = account.libraryGet(listOf(imdb)).firstOrNull()?.get("state") as? JsonObject
            ?: return RemoteWatched(false, emptySet())
        if (type == MediaType.Movie) return RemoteWatched(state.long("timesWatched") > 0 || state.long("flaggedWatched") > 0, emptySet())
        val serialized = state.string("watched") ?: return RemoteWatched(false, emptySet())
        return RemoteWatched(false, WatchedBitfield.parse(serialized, addons.seriesVideos(imdb).map { it.id }).watched)
    }

    /**
     * Marks a movie, or some videos of a series ([videoIds] like "tt…:1:2"), watched or not in the
     * account, the way Stremio does: a movie counts as watched and leaves "continue watching"; a
     * series sets its bits and, with [advance], moves "continue watching" past a now watched episode.
     */
    fun setWatched(type: MediaType, imdb: String, title: String, poster: String?, videoIds: List<String>, watched: Boolean, advance: Boolean = true) {
        if (!signedIn) return
        scope.launch {
            runCatching { marksMutex.withLock { applyWatched(type, imdb, title, poster, videoIds, watched, advance) } }
        }
    }

    /** Stremio's per-title "notify me about new videos" (the bell), kept in step with Harbor's. */
    fun setNotify(imdb: String, notify: Boolean) {
        if (!signedIn) return
        scope.launch {
            runCatching {
                marksMutex.withLock {
                    val existing = account.libraryGet(listOf(imdb)).firstOrNull() ?: return@withLock
                    val state = existing["state"] as? JsonObject ?: return@withLock
                    val now = JsonPrimitive(iso(System.currentTimeMillis()))
                    account.libraryPut(listOf(JsonObject(existing + mapOf("state" to JsonObject(state + ("noNotif" to JsonPrimitive(!notify))), "_mtime" to now))))
                }
            }
        }
    }

    private suspend fun applyWatched(type: MediaType, imdb: String, title: String, poster: String?, videoIds: List<String>, watched: Boolean, advance: Boolean) {
        val base = account.libraryGet(listOf(imdb)).firstOrNull() ?: newItem(imdb, type, title, poster)
        val state = base["state"] as? JsonObject ?: JsonObject(emptyMap())
        val now = iso(System.currentTimeMillis())
        val changes = mutableMapOf<String, JsonElement>()
        if (type == MediaType.Movie) {
            val already = state.long("timesWatched") > 0 || state.long("flaggedWatched") > 0
            if (watched) {
                if (!already) changes["timesWatched"] = JsonPrimitive(state.long("timesWatched") + 1)
                changes["flaggedWatched"] = JsonPrimitive(1)
                changes["lastWatched"] = JsonPrimitive(now)
                if (advance) changes["timeOffset"] = JsonPrimitive(0)
            } else {
                changes["timesWatched"] = JsonPrimitive(0)
                changes["flaggedWatched"] = JsonPrimitive(0)
            }
        } else {
            val videos = addons.seriesVideos(imdb)
            val bits = WatchedBitfield.parse(state.string("watched"), videos.map { it.id })
            videoIds.forEach { bits[it] = watched }
            changes["watched"] = JsonPrimitive(bits.serialize())
            val current = state.string("video_id")
            if (watched && advance && current != null && current in videoIds) {
                val nowMs = System.currentTimeMillis()
                val next = videos.dropWhile { it.id != current }.drop(1)
                    .firstOrNull { it.season != 0 && !bits[it.id] && (it.released ?: 0L) <= nowMs }
                if (next != null) {
                    // Like Stremio: "continue watching" moves on to the next episode, from the start.
                    changes["video_id"] = JsonPrimitive(next.id)
                    changes["timeOffset"] = JsonPrimitive(1)
                    changes["overallTimeWatched"] = JsonPrimitive(state.long("overallTimeWatched") + state.long("timeWatched"))
                    changes["timeWatched"] = JsonPrimitive(0)
                    changes["flaggedWatched"] = JsonPrimitive(0)
                } else {
                    changes["timeOffset"] = JsonPrimitive(0)
                }
            }
        }
        account.libraryPut(listOf(JsonObject(base + mapOf("state" to JsonObject(state + changes), "_mtime" to JsonPrimitive(now)))))
    }

    private suspend fun imdbFor(item: MediaItem): String? = runCatching { meta.details(item.type, item.id).imdbId }.getOrNull()

    private suspend fun pushProgress(entry: ProgressEntry, imdb: String) {
        val existing = account.libraryGet(listOf(imdb)).firstOrNull()
        val base = existing ?: newItem(imdb, entry.type, entry.title, entry.poster)
        val state = base["state"] as? JsonObject ?: JsonObject(emptyMap())
        val videoId = if (entry.season != null && entry.episode != null) "$imdb:${entry.season}:${entry.episode}" else imdb
        val sameVideo = state.string("video_id") == videoId
        val prevOffset = state.long("timeOffset")
        val delta = if (sameVideo) (entry.positionMs - prevOffset).coerceAtLeast(0) else entry.positionMs
        val justFinished = entry.finished && (prevOffset > 0 || !sameVideo)
        // A finished episode also gets its "watched" bit, as Stremio does when an episode ends.
        val watchedBits = if (entry.finished && entry.season != null && entry.episode != null) {
            runCatching {
                WatchedBitfield.parse(state.string("watched"), addons.seriesVideos(imdb).map { it.id }).also { it[videoId] = true }.serialize()
            }.getOrNull()
        } else null
        val now = iso(System.currentTimeMillis())
        val newState = JsonObject(
            state + mapOf(
                "lastWatched" to JsonPrimitive(now),
                // Finished: back to 0, which is also how Stremio takes it out of "continue watching".
                "timeOffset" to JsonPrimitive(if (entry.finished) 0 else entry.positionMs),
                "duration" to JsonPrimitive(entry.durationMs),
                "video_id" to JsonPrimitive(videoId),
                "timeWatched" to JsonPrimitive(if (sameVideo) state.long("timeWatched") + delta else delta),
                "overallTimeWatched" to JsonPrimitive(state.long("overallTimeWatched") + delta),
            ) + if (justFinished) {
                mapOf("timesWatched" to JsonPrimitive(state.long("timesWatched") + 1)) +
                    if (entry.type == MediaType.Movie) mapOf("flaggedWatched" to JsonPrimitive(1)) else emptyMap()
            } else {
                emptyMap()
            } + (watchedBits?.let { mapOf("watched" to JsonPrimitive(it)) } ?: emptyMap()),
        )
        account.libraryPut(listOf(JsonObject(base + mapOf("state" to newState, "_mtime" to JsonPrimitive(now)))))
    }

    private fun libraryEntry(existing: JsonObject?, imdb: String, type: MediaType, title: String, poster: String?, inLibrary: Boolean): JsonObject {
        val base = existing ?: newItem(imdb, type, title, poster)
        val now = iso(System.currentTimeMillis())
        val changes = if (inLibrary) {
            mapOf("removed" to JsonPrimitive(false), "temp" to JsonPrimitive(false))
        } else {
            mapOf("removed" to JsonPrimitive(true))
        }
        return JsonObject(base + changes + ("_mtime" to JsonPrimitive(now)))
    }

    /** A library item the way Stremio writes one: watched but not in the list until said otherwise. */
    private fun newItem(imdb: String, type: MediaType, title: String, poster: String?): JsonObject {
        val now = iso(System.currentTimeMillis())
        return buildJsonObject {
            put("_id", imdb)
            put("name", title)
            put("type", type.stremio)
            put("poster", Img.poster(poster, "w500"))
            put("posterShape", "poster")
            put("removed", true)
            put("temp", true)
            put("_ctime", now)
            put("_mtime", now)
            putJsonObject("state") {
                put("lastWatched", now)
                put("timeWatched", 0)
                put("timeOffset", 0)
                put("overallTimeWatched", 0)
                put("timesWatched", 0)
                put("flaggedWatched", 0)
                put("duration", 0)
                put("video_id", null as String?)
                put("noNotif", false)
            }
            putJsonObject("behaviorHints") {
                put("defaultVideoId", null as String?)
                put("featuredVideoId", null as String?)
                put("hasScheduledVideos", false)
            }
        }
    }

    private class RemoteItem(
        val raw: JsonObject,
        val id: String,
        val type: MediaType,
        val removed: Boolean,
        val temp: Boolean,
        val ctime: Long?,
        val mtime: Long,
        val lastWatched: Long?,
        val timeOffset: Long,
        val duration: Long,
        val videoId: String?,
    ) {
        val key: String get() = "${type.stremio}:$id"
        val watchedAt: Long get() = lastWatched ?: mtime
        val inLibrary: Boolean get() = !removed && !temp
        val inContinueWatching: Boolean get() = (!removed || temp) && timeOffset > 0

        /** Season and episode from a video id like "tt0944947:1:2" (or "tmdb:1399:1:2"). */
        fun episode(): Pair<Int?, Int?> {
            if (type != MediaType.Tv) return null to null
            val parts = videoId?.split(':').orEmpty()
            if (parts.size < 3) return null to null
            return parts[parts.size - 2].toIntOrNull() to parts.last().toIntOrNull()
        }

        companion object {
            fun parse(o: JsonObject): RemoteItem? {
                val type = when (o.string("type")) {
                    "movie" -> MediaType.Movie
                    "series" -> MediaType.Tv
                    else -> return null
                }
                val state = o["state"] as? JsonObject ?: JsonObject(emptyMap())
                return RemoteItem(
                    raw = o,
                    id = o.string("_id") ?: return null,
                    type = type,
                    removed = o.bool("removed"),
                    temp = o.bool("temp"),
                    ctime = time(o.string("_ctime")),
                    mtime = time(o.string("_mtime")) ?: 0L,
                    lastWatched = time(state.string("lastWatched")),
                    timeOffset = state.long("timeOffset"),
                    duration = state.long("duration"),
                    videoId = state.string("video_id"),
                )
            }
        }
    }

    private companion object {
        const val MAX_CONTINUE = 30
        const val SKEW_MS = 5_000L

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
        fun time(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun JsonObject.prim(name: String): JsonPrimitive? = this[name] as? JsonPrimitive
        fun JsonObject.string(name: String): String? = prim(name)?.takeIf { it.isString }?.content
        fun JsonObject.long(name: String): Long = prim(name)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
        fun JsonObject.bool(name: String): Boolean = prim(name)?.booleanOrNull ?: false
    }
}
