package com.synrg.harbor.core

import android.app.Application
import com.synrg.harbor.cast.CastManager
import com.synrg.harbor.data.ProgressData
import com.synrg.harbor.downloads.AutoDownloads
import com.synrg.harbor.downloads.DownloadData
import com.synrg.harbor.downloads.Downloads
import com.synrg.harbor.episodes.CalendarData
import com.synrg.harbor.episodes.EpisodeCalendar
import com.synrg.harbor.data.ProgressRepository
import com.synrg.harbor.data.Settings
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.data.WatchLog
import com.synrg.harbor.data.WatchLogRepository
import com.synrg.harbor.data.WatchlistData
import com.synrg.harbor.data.WatchlistRepository
import com.synrg.harbor.stremio.AddonRepository
import com.synrg.harbor.stremio.StremioAccount
import com.synrg.harbor.stremio.StremioSync
import com.synrg.harbor.stremio.WatchedMarks
import com.synrg.harbor.tmdb.Metadata
import com.synrg.harbor.tmdb.TmdbAddon
import com.synrg.harbor.tmdb.TmdbApi
import com.synrg.harbor.tmdb.Wikidata
import com.synrg.harbor.torrent.TorrentEngine
import com.synrg.harbor.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies: no DI framework, nothing is created before it is needed. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "http"), 64L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .addNetworkInterceptor(ApiCacheInterceptor)
            .build()
    }

    /** Shares the connection pool with [http]; Coil keeps its own disk cache. */
    val imageHttp: OkHttpClient by lazy { http.newBuilder().cache(null).build() }

    /** For media: no response cache, long reads (torrent pieces can take a while to arrive). */
    val mediaHttp: OkHttpClient by lazy {
        http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
    }

    val settings: SettingsRepository by lazy { SettingsRepository(store("settings.json", Settings.serializer(), Settings())) }
    val progress: ProgressRepository by lazy { ProgressRepository(store("progress.json", ProgressData.serializer(), ProgressData())) }
    val watchlist: WatchlistRepository by lazy { WatchlistRepository(store("watchlist.json", WatchlistData.serializer(), WatchlistData())) }
    val watchLog: WatchLogRepository by lazy { WatchLogRepository(store("watchlog.json", WatchLog.serializer(), WatchLog())) }
    val backup: Backup by lazy { Backup(this) }

    val meta: Metadata by lazy { Metadata(settings, TmdbApi(http, json, settings), TmdbAddon(http, json, settings), Wikidata(http, json)) }
    val addons: AddonRepository by lazy { AddonRepository(http, json, settings) }
    val stremio: StremioAccount by lazy { StremioAccount(http, json, settings, addons) }
    val sync: StremioSync by lazy { StremioSync(stremio, addons, settings, progress, watchlist, meta, scope) }
    val marks: WatchedMarks by lazy { WatchedMarks(progress, sync) }
    val episodes: EpisodeCalendar by lazy {
        EpisodeCalendar(store("episodes.json", CalendarData.serializer(), CalendarData()), addons, meta, progress, watchlist, sync)
    }
    val network: NetworkMonitor by lazy { NetworkMonitor(app) }
    val torrents: TorrentEngine by lazy { TorrentEngine(app, network, scope) }
    val updater: Updater by lazy { Updater(app, http, json, settings, scope) }
    val downloads: Downloads by lazy {
        Downloads(app, store("downloads.json", DownloadData.serializer(), DownloadData()), torrents, mediaHttp, network, settings, progress, addons)
    }
    val cast: CastManager by lazy { CastManager(app, mediaHttp, scope) }
    val autoDownloads: AutoDownloads by lazy { AutoDownloads(downloads, addons, meta, settings, torrents, scope) }

    /** What a list already knew about a title, so its detail page can paint before the full data arrives. */
    val previews = java.util.concurrent.ConcurrentHashMap<String, com.synrg.harbor.data.MediaItem>()

    private fun <T> store(name: String, serializer: KSerializer<T>, default: T) =
        JsonStore(File(app.filesDir, name), serializer, default, json, scope)
}
