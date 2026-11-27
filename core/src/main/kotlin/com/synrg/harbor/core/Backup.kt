package com.synrg.harbor.core

import com.synrg.harbor.data.ProgressData
import com.synrg.harbor.data.ProgressRepository
import com.synrg.harbor.data.Settings
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.data.WatchLog
import com.synrg.harbor.data.WatchLogRepository
import com.synrg.harbor.data.WatchlistData
import com.synrg.harbor.data.WatchlistRepository
import com.synrg.harbor.episodes.EpisodeCalendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
class Backup(
    private val json: Json,
    private val appVersion: String,
    private val settings: SettingsRepository,
    private val progress: ProgressRepository,
    private val watchlist: WatchlistRepository,
    private val episodes: EpisodeCalendar,
    private val watchLog: WatchLogRepository,
) {
    class Result(val progress: Int, val list: Int)

    fun export(out: OutputStream) {
        val saved = settings.current.copy(stremioAuthKey = null, stremioEmail = null, tmdbKey = "")
        val file = BackupFile(
            appVersion = appVersion,
            exportedAt = System.currentTimeMillis(),
            settings = saved,
            progress = progress.data,
            watchlist = WatchlistData(watchlist.entries),
            muted = episodes.state.value.muted,
            watchLog = watchLog.value,
        )
        out.bufferedWriter().use { it.write(json.encodeToString(BackupFile.serializer(), file)) }
    }

    fun import(input: InputStream): Result {
        val text = input.bufferedReader().use { it.readText() }
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()
        if (file == null || file.format != FORMAT) throw IOException("not a Harbor backup")
        file.settings?.let { saved ->
            settings.update { current ->
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
        val merged = progress.merge(file.progress)
        val list = file.watchlist.entries.count { e -> watchlist.entries.none { it.item.type == e.item.type && it.item.id == e.item.id } }
        file.watchlist.entries.forEach(watchlist::add)
        file.muted.forEach { episodes.setMuted(it, null, muted = true) }
        watchLog.merge(file.watchLog)
        return Result(merged, list)
    }

    companion object {
        const val FORMAT = "harbor-backup"
    }
}
