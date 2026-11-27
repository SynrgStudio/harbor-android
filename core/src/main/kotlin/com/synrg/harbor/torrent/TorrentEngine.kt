package com.synrg.harbor.torrent

import androidx.compose.runtime.Immutable
import com.synrg.harbor.core.CoreStrings
import com.synrg.harbor.core.CoreText
import com.synrg.harbor.core.HttpException
import com.synrg.harbor.core.NetworkMonitor
import com.synrg.harbor.core.await
import com.synrg.harbor.data.SavedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Starts librqbit and its HTTP API, the way the platform runs it (on Android, the Rust library in
 * native/ through JNI). Everything after that goes through the HTTP API.
 */
interface TorrentBackend {
    /** False when the engine isn't in this build (or didn't load). */
    val available: Boolean

    /** Starts the session (idempotent). Returns the HTTP API port on 127.0.0.1, or -1. */
    fun start(outputDir: String, persistenceDir: String): Int

    fun lastError(): String?
}

@Immutable
data class TorrentStats(
    val downloadBps: Long,
    val peers: Int,
    val progress: Float,
    val state: String,
)

@Immutable
data class TorrentStream(val url: String, val torrentId: Int, val fileName: String?, val fileIdx: Int = 0)

/** How far a download's file is, and where librqbit is writing it. */
data class FileProgress(val done: Long, val total: Long, val downloadBps: Long, val peers: Int, val file: File?) {
    val complete: Boolean get() = total > 0 && done >= total
}

/** Most Torrentio results carry no trackers; like Stremio, add well-known public ones. */
private val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://tracker.theoks.net:6969/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker-udp.gbitt.info:80/announce",
    "udp://opentracker.io:6969/announce",
    "http://tracker.opentrackr.org:1337/announce",
    "https://tracker.tamersunion.org:443/announce",
)

private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "webm", "mov", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg")

/**
 * Streams torrents with librqbit (the same engine as Harbor desktop). Kotlin talks to its local
 * HTTP API: add a magnet, then play /torrents/{id}/stream/{file} (Range requests, with piece
 * priority following the playback position).
 */
class TorrentEngine(
    private val backend: TorrentBackend,
    /** The torrent cache: what is being streamed or downloaded. */
    private val outputDir: File,
    /** librqbit's session state. */
    private val stateDir: File,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    val available: Boolean get() = backend.available

    private val mutex = Mutex()
    private var port = -1
    /** Torrents a download is using: closing the player must not pause them. */
    private val pinned = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Torrents the player has open: a finished download waits for them before moving the file. */
    private val streaming = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun isStreaming(torrentId: Int) = torrentId in streaming
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private suspend fun ensureStarted(): Int = mutex.withLock {
        if (port > 0) return port
        if (!available) throw IOException(CoreStrings.get(CoreText.TorrentEngineMissing))
        withContext(Dispatchers.IO) {
            outputDir.mkdirs()
            stateDir.mkdirs()
            pruneCache()
            val p = backend.start(outputDir.absolutePath, stateDir.absolutePath)
            if (p <= 0) throw IOException(CoreStrings.get(CoreText.TorrentEngineNotStarted, backend.lastError() ?: CoreStrings.get(CoreText.TorrentUnknownError)))
            port = p
        }
        watchNetwork()
        port
    }

    /**
     * On mobile data the engine keeps downloading but barely seeds: one 16 KiB block per second,
     * the smallest limit librqbit accepts without dropping requests (no request is bigger).
     */
    private fun watchNetwork() {
        scope.launch {
            network.state.map { it.metered }.distinctUntilChanged().collect { metered ->
                val upload = if (metered) "16384" else "null"
                runCatching {
                    call(
                        Request.Builder().url("$base/torrents/limits")
                            .post("""{"upload_bps":$upload,"download_bps":null}""".toRequestBody("application/json".toMediaType()))
                            .build(),
                    )
                }
            }
        }
    }

    private val base get() = "http://127.0.0.1:$port"

    /** Keeps the torrent cache bounded: drops folders untouched for 2 days, then oldest first above 12 GB. */
    private fun pruneCache() {
        val dirs = outputDir.listFiles().orEmpty().toMutableList()
        val now = System.currentTimeMillis()
        dirs.filter { now - it.lastModified() > 48L * 3600_000 }.forEach { it.deleteRecursively(); dirs.remove(it) }
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val limit = 12L * 1024 * 1024 * 1024
        for (d in dirs.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            val size = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively()
            total -= size
        }
    }

    private suspend fun call(request: Request): JsonObject {
        val response = http.newCall(request).await()
        val text = response.use { withContext(Dispatchers.IO) { it.body.string() } }
        if (!response.isSuccessful) throw HttpException(response.code, CoreStrings.get(CoreText.TorrentEngineError, text.take(200)))
        return json.parseToJsonElement(text).jsonObject
    }

    suspend fun open(stream: SavedStream): TorrentStream = add(stream).also { streaming += it.torrentId }

    private suspend fun add(stream: SavedStream): TorrentStream {
        val hash = stream.infoHash ?: throw IOException(CoreStrings.get(CoreText.TorrentNoHash))
        val port = ensureStarted()
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            (stream.trackers + DEFAULT_TRACKERS).distinct().take(40).forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val query = buildString {
            append("overwrite=true")
            stream.fileIdx?.let { append("&only_files=").append(it) }
        }
        val added = call(
            Request.Builder().url("http://127.0.0.1:$port/torrents?$query")
                .post(magnet.toRequestBody("text/plain".toMediaType()))
                .build(),
        )
        val id = added["id"]?.jsonPrimitive?.intOrNull ?: throw IOException(CoreStrings.get(CoreText.TorrentNotReturned))
        val files = added["details"]?.jsonObject?.get("files")?.jsonArray.orEmpty().mapIndexed { i, f ->
            val o = f.jsonObject
            Triple(i, o["name"]?.jsonPrimitive?.contentOrNull ?: "", o["length"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        val fileIdx = stream.fileIdx ?: files
            .filter { (_, name, _) -> name.substringAfterLast('.', "").lowercase() in VIDEO_EXT }
            .maxByOrNull { it.third }?.first
            ?: files.maxByOrNull { it.third }?.first
            ?: 0
        if (stream.fileIdx == null) {
            // Only download the chosen file.
            runCatching {
                call(
                    Request.Builder().url("$base/torrents/$id/update_only_files")
                        .post("""{"only_files":[$fileIdx]}""".toRequestBody("application/json".toMediaType()))
                        .build(),
                )
            }
        }
        // Re-adding a known torrent returns it as it was (possibly paused by a previous close()).
        runCatching { call(Request.Builder().url("$base/torrents/$id/start").post(ByteArray(0).toRequestBody()).build()) }
        val name = files.getOrNull(fileIdx)?.second
        return TorrentStream("$base/torrents/$id/stream/$fileIdx", id, name, fileIdx)
    }

    /** Like [open], for a download: the torrent keeps running until [forget] (or [pause]). */
    suspend fun openForDownload(stream: SavedStream): TorrentStream = add(stream).also { pinned += it.torrentId }

    suspend fun pause(torrentId: Int) {
        if (port <= 0) return
        runCatching { call(Request.Builder().url("$base/torrents/$torrentId/pause").post(ByteArray(0).toRequestBody()).build()) }
    }

    /** Drops the torrent from the session but keeps its files (a finished download moves them out). */
    suspend fun forget(torrentId: Int) {
        pinned -= torrentId
        if (port <= 0) return
        runCatching { call(Request.Builder().url("$base/torrents/$torrentId/forget").post(ByteArray(0).toRequestBody()).build()) }
    }

    suspend fun fileProgress(torrentId: Int, fileIdx: Int): FileProgress {
        val details = call(Request.Builder().url("$base/torrents/$torrentId").build())
        val entry = details["files"]?.jsonArray?.getOrNull(fileIdx)?.jsonObject
        val folder = details["output_folder"]?.jsonPrimitive?.contentOrNull
        val components = entry?.get("components")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val file = if (folder != null && components.isNotEmpty()) File(folder, components.joinToString(File.separator)) else null
        val length = entry?.get("length")?.jsonPrimitive?.longOrNull ?: 0L
        val stats = call(Request.Builder().url("$base/torrents/$torrentId/stats/v1").build())
        val done = stats["file_progress"]?.jsonArray?.getOrNull(fileIdx)?.jsonPrimitive?.longOrNull ?: 0L
        val live = stats["live"] as? JsonObject
        val speed = live?.get("download_speed")?.jsonObject?.get("mbps")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val peers = live?.get("snapshot")?.jsonObject?.get("peer_stats")?.jsonObject?.get("live")?.jsonPrimitive?.intOrNull ?: 0
        return FileProgress(done.coerceAtMost(length), length, (speed * 1_048_576).toLong(), peers, file)
    }

    fun stats(torrentId: Int): Flow<TorrentStats?> = flow {
        while (true) {
            val stats = runCatching {
                val o = call(Request.Builder().url("$base/torrents/$torrentId/stats/v1").build())
                val live = o["live"] as? JsonObject
                val speed = live?.get("download_speed")?.jsonObject?.get("mbps")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                val peers = live?.get("snapshot")?.jsonObject?.get("peer_stats")?.jsonObject?.get("live")?.jsonPrimitive?.intOrNull ?: 0
                val total = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                val done = o["progress_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                TorrentStats(
                    downloadBps = (speed * 1_048_576).toLong(),
                    peers = peers,
                    progress = if (total > 0) done.toFloat() / total else 0f,
                    state = o["state"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }.getOrNull()
            emit(stats)
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO)

    /** Stops downloading but keeps the data, so reopening the same source is instant. */
    suspend fun close(torrentId: Int) {
        streaming -= torrentId
        if (port <= 0 || torrentId in pinned) return
        runCatching { call(Request.Builder().url("$base/torrents/$torrentId/pause").post(ByteArray(0).toRequestBody()).build()) }
    }
}
