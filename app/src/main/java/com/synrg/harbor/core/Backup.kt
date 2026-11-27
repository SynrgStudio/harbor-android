package com.synrg.harbor.core

import com.synrg.harbor.BuildConfig
import com.synrg.harbor.data.ProgressData
import com.synrg.harbor.data.Settings
import com.synrg.harbor.data.WatchLog
import com.synrg.harbor.data.WatchlistData
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The backup file: everything that lives only on the phone, in one JSON file the user keeps. */
@Serializable
class BackupFile(
    val format: String = Backup.FORMAT,
    val version: Int = 1,
    val appVersion: String = "",
    val exportedAt: Long = 0,
    val settings: Settings? = null,
    val progress: ProgressData = ProgressData(),
    val watchlist: WatchlistData = WatchlistData(),
    /** Series whose new-episode notifications are off. */
    val muted: Set<Int> = emptySet(),
    val watchLog: WatchLog = WatchLog(),
)

/**
 * Export and import of what Harbor keeps on the phone: progress (with per-title audio, subtitle and
 * intro choices), My list, muted series, settings with the addons, and the stats' time log.
 * The Stremio sign-in and the TMDB key are never written. Importing merges: newer progress wins,
 * lists are joined, and the settings come back without touching the sign-in or the key.
 */
class Backup(private val graph: AppGraph) {
    class Result(val progress: Int, val list: Int)

    fun export(out: OutputStream) {
        val settings = graph.settings.current.copy(stremioAuthKey = null, stremioEmail = null, tmdbKey = "")
        val file = BackupFile(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            settings = settings,
            progress = graph.progress.data,
            watchlist = WatchlistData(graph.watchlist.entries),
            muted = graph.episodes.state.value.muted,
            watchLog = graph.watchLog.value,
        )
        out.bufferedWriter().use { it.write(graph.json.encodeToString(BackupFile.serializer(), file)) }
    }

    fun import(input: InputStream): Result {
        val text = input.bufferedReader().use { it.readText() }
        val file = runCatching { graph.json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()
        if (file == null || file.format != FORMAT) throw IOException("not a Harbor backup")
        file.settings?.let { saved ->
            graph.settings.update { current ->
                saved.copy(
                    stremioAuthKey = current.stremioAuthKey,
                    stremioEmail = current.stremioEmail,
                    tmdbKey = current.tmdbKey,
                    // Addons on both sides stay; the phone's own copy wins for the same addon.
                    addons = current.addons + saved.addons.filter { a -> current.addons.none { it.id == a.id } },
                    addonsSeeded = true,
                    notificationPromptShown = current.notificationPromptShown,
                    dismissedUpdate = current.dismissedUpdate,
                )
            }
        }
        val progress = graph.progress.merge(file.progress)
        val list = file.watchlist.entries.count { e -> graph.watchlist.entries.none { it.item.type == e.item.type && it.item.id == e.item.id } }
        file.watchlist.entries.forEach(graph.watchlist::add)
        file.muted.forEach { graph.episodes.setMuted(it, null, muted = true) }
        graph.watchLog.merge(file.watchLog)
        return Result(progress, list)
    }

    companion object {
        const val FORMAT = "harbor-backup"
    }
}
