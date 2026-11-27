package com.synrg.harbor.downloads

import android.app.Application
import android.content.Intent
import android.os.Environment
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.synrg.harbor.R
import com.synrg.harbor.core.JsonStore
import com.synrg.harbor.core.NetworkMonitor
import com.synrg.harbor.core.Strings
import com.synrg.harbor.core.await
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.ProgressRepository
import com.synrg.harbor.data.SavedStream
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.player.Languages
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.stremio.AddonRepository
import com.synrg.harbor.stremio.StremioSubtitle
import com.synrg.harbor.torrent.TorrentEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@Serializable
enum class DownloadStatus { Queued, Downloading, WaitingWifi, Paused, Done, Failed }

/** A subtitle saved with a download: [url] is the local file, [source] the address it came from. */
@Immutable
@Serializable
data class SavedSubtitle(val url: String, val source: String, val lang: String? = null)

/** One downloaded (or downloading) movie or episode; [id] is its progress key, so one per video. */
@Immutable
@Serializable
data class DownloadItem(
    val id: String,
    val type: MediaType,
    val tmdbId: Int,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val still: String? = null,
    val stream: SavedStream,
    val status: DownloadStatus = DownloadStatus.Queued,
    val bytes: Long = 0,
    val total: Long = 0,
    /** Where the finished file is. */
    val file: String? = null,
    val error: String? = null,
    val addedAt: Long = 0,
    val subtitles: List<SavedSubtitle> = emptyList(),
) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = status == DownloadStatus.Queued || status == DownloadStatus.Downloading || status == DownloadStatus.WaitingWifi
    val localFile: File? get() = file?.let(::File)?.takeIf { status == DownloadStatus.Done && it.isFile }

    /** Plays the file, from where the progress says it was left. */
    fun playback(resumeMs: Long = 0) = PlaybackRequest(
        type = type, tmdbId = tmdbId, imdbId = imdbId, season = season, episode = episode,
        title = title, episodeTitle = episodeTitle, poster = poster, backdrop = backdrop, still = still,
        stream = SavedStream(url = "file://" + file, filename = file?.substringAfterLast('/'), addonName = Strings.get(R.string.downloads_downloaded), label = stream.label),
        startPositionMs = resumeMs,
    )
}

@Serializable
data class DownloadData(val items: List<DownloadItem> = emptyList())

/** Live numbers for the screen and the notification, not worth saving. */
data class DownloadSpeed(val bps: Long = 0, val peers: Int? = null)

/**
 * Saves movies and episodes to watch without internet. Torrents download with librqbit into its
 * cache and, once complete, the file moves to app storage; direct links download with OkHttp,
 * resuming where they stopped. [DownloadService] runs [runQueue] in the foreground.
 */
class Downloads(
    private val app: Application,
    private val store: JsonStore<DownloadData>,
    private val torrents: TorrentEngine,
    private val http: OkHttpClient,
    private val network: NetworkMonitor,
    private val settings: SettingsRepository,
    private val progress: ProgressRepository,
    private val addons: AddonRepository,
) {
    val items: StateFlow<DownloadData> = store.state
    val list get() = store.value.items

    private val _speed = MutableStateFlow<Map<String, DownloadSpeed>>(emptyMap())
    val speed: StateFlow<Map<String, DownloadSpeed>> = _speed

    /** Where new downloads go: the SD card when chosen (and still there), else the phone. */
    val dir: File
        get() = (if (settings.current.downloadStorage == "sd") sdDir() else null)
            ?: File(app.filesDir, "downloads").apply { mkdirs() }

    /**
     * The app's own folder on a removable card, when one is mounted. No permission needed; Android
     * deletes it with the app. Torrents finish in the engine's cache on the phone and are copied
     * there (a rename only works within one storage).
     */
    fun sdDir(): File? = app.getExternalFilesDirs(null).drop(1).firstOrNull { folder ->
        folder != null && runCatching {
            Environment.isExternalStorageRemovable(folder) && Environment.getExternalStorageState(folder) == Environment.MEDIA_MOUNTED
        }.getOrDefault(false)
    }?.let { File(it, "downloads").apply { mkdirs() } }

    /** Finished since the service last asked (for its "ready to watch" notification). */
    private val finished = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun takeFinished(): List<DownloadItem> {
        val ids = finished.toSet()
        finished.removeAll(ids)
        return store.value.items.filter { it.id in ids && it.status == DownloadStatus.Done }
    }

    fun get(id: String): DownloadItem? = store.value.items.firstOrNull { it.id == id }

    /** The finished download of a video, if its file is still there. */
    fun local(key: String): DownloadItem? = get(key)?.takeIf { it.localFile != null }

    fun add(item: DownloadItem) {
        val previous = get(item.id)
        if (previous != null) deleteFiles(previous)
        store.update { d -> d.copy(items = d.items.filterNot { it.id == item.id } + item.copy(status = DownloadStatus.Queued, addedAt = System.currentTimeMillis())) }
        store.flushNow()
        startService()
    }

    fun pause(id: String) = setStatus(id, DownloadStatus.Paused)

    fun resume(id: String) {
        setStatus(id, DownloadStatus.Queued)
        startService()
    }

    fun remove(id: String) {
        val item = get(id) ?: return
        store.update { d -> d.copy(items = d.items.filterNot { it.id == id }) }
        store.flushNow()
        deleteFiles(item)
    }

    /** After a reboot or an update: carry on with what was left halfway. */
    fun resumePending() {
        if (store.value.items.any { it.active }) startService()
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    private fun setStatus(id: String, status: DownloadStatus, error: String? = null) =
        store.update { d -> d.copy(items = d.items.map { if (it.id == id) it.copy(status = status, error = error) else it }) }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) =
        store.update { d -> d.copy(items = d.items.map { if (it.id == id) transform(it) else it }) }

    private fun deleteFiles(item: DownloadItem) {
        item.file?.let { File(it).delete() }
        partFile(item).delete()
        item.subtitles.forEach { File(it.url.removePrefix("file://")).delete() }
    }

    /** The original address of a subtitle saved with a download (or [url] itself). */
    fun subtitleSource(url: String): String =
        if (!url.startsWith("file://")) url
        else store.value.items.firstNotNullOfOrNull { d -> d.subtitles.firstOrNull { it.url == url }?.source } ?: url

    /**
     * Keeps subtitles for watching offline: the internet one picked for this video, and the best
     * few in the title's subtitle language. Done while there is internet, before the video itself.
     */
    private suspend fun saveSubtitles(item: DownloadItem) {
        if (item.subtitles.isNotEmpty()) return
        val imdb = item.imdbId ?: return
        val stremioId = if (item.season != null && item.episode != null) "$imdb:${item.season}:${item.episode}" else imdb
        val pref = progress.trackPref(item.type, item.tmdbId)
        val lang = pref?.subtitle ?: settings.current.subtitleLanguage
        val chosen = pref?.subtitleChoice?.takeIf { it.video == item.id && it.externalUrl != null }
        val all = runCatching { addons.subtitles(item.type.stremio, stremioId) }.getOrDefault(emptyList())
        val picks = buildList {
            chosen?.let { c -> add(all.firstOrNull { it.url == c.externalUrl } ?: StremioSubtitle(c.externalId, c.externalUrl!!, c.language)) }
            if (lang.isNotEmpty()) addAll(all.filter { Languages.matches(it.lang, lang) }.take(3))
        }.distinctBy { it.url }.take(4)
        val saved = picks.mapIndexedNotNull { i, sub ->
            runCatching {
                val ext = sub.url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                val file = File(dir, "${safeName(item)}.sub$i.$ext")
                val response = http.newCall(Request.Builder().url(sub.url).build()).await()
                response.use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    withContext(Dispatchers.IO) { file.outputStream().use { res.body.byteStream().copyTo(it) } }
                }
                SavedSubtitle("file://" + file.absolutePath, sub.url, sub.lang)
            }.getOrNull()
        }
        if (saved.isNotEmpty()) updateItem(item.id) { it.copy(subtitles = saved) }
    }

    private fun partFile(item: DownloadItem) = File(dir, safeName(item) + ".part")

    private fun safeName(item: DownloadItem) = item.id.replace(':', '_')

    // --- The queue (runs inside DownloadService) ------------------------------------------------

    /** Downloads one item at a time until nothing is left to do. */
    suspend fun runQueue(onUpdate: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            val next = store.value.items.firstOrNull { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.WaitingWifi }
                ?: store.value.items.firstOrNull { it.status == DownloadStatus.Queued }
                ?: break
            if (settings.current.downloadWifiOnly && network.current.metered) {
                store.value.items.filter { it.active }.forEach { setStatus(it.id, DownloadStatus.WaitingWifi) }
                onUpdate()
                // Re-checked every half minute too: the setting may be switched off meanwhile.
                withTimeoutOrNull(30_000) { network.state.first { !it.metered } }
                continue
            }
            setStatus(next.id, DownloadStatus.Downloading)
            onUpdate()
            runCatching { saveSubtitles(next) }
            try {
                if (next.stream.url == null && next.stream.infoHash != null) downloadTorrent(next, onUpdate) else downloadHttp(next, onUpdate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (get(next.id)?.status == DownloadStatus.Downloading) {
                    setStatus(next.id, DownloadStatus.Failed, e.message ?: Strings.get(R.string.downloads_failed))
                }
            }
            _speed.value = _speed.value - next.id
            store.flushNow()
            onUpdate()
        }
    }

    /** True while this item should keep going (not paused, removed or waiting for Wi-Fi). */
    private fun stillWanted(id: String): Boolean {
        val item = get(id) ?: return false
        if (item.status != DownloadStatus.Downloading) return false
        if (settings.current.downloadWifiOnly && network.current.metered) {
            setStatus(id, DownloadStatus.WaitingWifi)
            return false
        }
        return true
    }

    private suspend fun downloadTorrent(item: DownloadItem, onUpdate: () -> Unit) {
        val torrent = torrents.openForDownload(item.stream)
        var lastUpdate = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!stillWanted(item.id)) {
                torrents.pause(torrent.torrentId)
                return
            }
            val p = runCatching { torrents.fileProgress(torrent.torrentId, torrent.fileIdx) }.getOrNull()
            if (p != null) {
                updateItem(item.id) { it.copy(bytes = p.done, total = p.total) }
                _speed.value = _speed.value + (item.id to DownloadSpeed(p.downloadBps, p.peers))
                if (System.currentTimeMillis() - lastUpdate > 1_500) {
                    lastUpdate = System.currentTimeMillis()
                    onUpdate()
                }
                val source = p.file
                if (p.complete && source != null && source.isFile) {
                    // Being watched right now (same source): moving the file would cut the stream.
                    while (torrents.isStreaming(torrent.torrentId)) delay(2_000)
                    torrents.forget(torrent.torrentId)
                    val target = File(dir, safeName(item) + "." + source.extension.ifEmpty { "mkv" })
                    withContext(Dispatchers.IO) {
                        target.delete()
                        // Same filesystem as the cache: a rename, not a copy.
                        if (!source.renameTo(target)) {
                            source.copyTo(target, overwrite = true)
                            source.delete()
                        }
                    }
                    updateItem(item.id) { it.copy(status = DownloadStatus.Done, file = target.absolutePath, bytes = p.total, total = p.total, error = null) }
                    finished += item.id
                    return
                }
            }
            delay(1_000)
        }
    }

    private suspend fun downloadHttp(item: DownloadItem, onUpdate: () -> Unit) {
        val url = item.stream.url ?: throw IOException(Strings.get(R.string.downloads_no_link))
        val part = partFile(item)
        val ext = (item.stream.filename ?: url.substringBefore('?')).substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: "mkv"
        val target = File(dir, safeName(item) + "." + ext)
        val already = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(url).apply {
            item.stream.headers.forEach { (k, v) -> header(k, v) }
            if (already > 0) header("Range", "bytes=$already-")
        }.build()
        val response = http.newCall(request).await()
        response.use { res ->
            if (!res.isSuccessful) throw IOException(Strings.get(R.string.downloads_stream_responded, res.code))
            val resumed = res.code == 206
            val start = if (resumed) already else 0L
            val total = start + (res.body.contentLength().takeIf { it > 0 } ?: 0L)
            withContext(Dispatchers.IO) {
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(start)
                    out.seek(start)
                    val input = res.body.byteStream()
                    val buffer = ByteArray(256 * 1024)
                    var written = start
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        windowBytes += n
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 1_000) {
                            if (!stillWanted(item.id)) return@withContext
                            _speed.value = _speed.value + (item.id to DownloadSpeed(windowBytes * 1000 / (now - windowStart)))
                            updateItem(item.id) { it.copy(bytes = written, total = total) }
                            onUpdate()
                            windowStart = now
                            windowBytes = 0
                        }
                    }
                }
            }
            if (get(item.id)?.status != DownloadStatus.Downloading) return
            target.delete()
            part.renameTo(target)
            updateItem(item.id) { it.copy(status = DownloadStatus.Done, file = target.absolutePath, bytes = target.length(), total = target.length(), error = null) }
            finished += item.id
        }
    }

    /** Bytes the finished downloads take. */
    val usedBytes get() = store.value.items.sumOf { it.localFile?.length() ?: 0L }

    companion object {
        fun key(type: MediaType, tmdbId: Int, season: Int?, episode: Int?) = ProgressEntry.key(type, tmdbId, season, episode)
    }
}

/** For the UI: the items, newest first. */
fun Downloads.sorted() = items.map { d -> d.items.sortedByDescending { it.addedAt } }
