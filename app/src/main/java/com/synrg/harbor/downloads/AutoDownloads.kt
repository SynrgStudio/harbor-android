package com.synrg.harbor.downloads

import com.synrg.harbor.data.Details
import com.synrg.harbor.data.Episode
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.data.SavedStream
import com.synrg.harbor.data.SettingsRepository
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.stremio.AddonRepository
import com.synrg.harbor.stremio.AddonStreams
import com.synrg.harbor.stremio.StreamOption
import com.synrg.harbor.stremio.StreamRules
import com.synrg.harbor.tmdb.Metadata
import com.synrg.harbor.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Downloads chosen by the app rather than by tapping ⬇ on a stream: a whole season, and smart
 * downloads (an episode watched from a download is deleted and the next one downloads). Streams
 * are picked with [StreamRules], keeping the release of the episode before when it can.
 */
class AutoDownloads(
    private val downloads: Downloads,
    private val addons: AddonRepository,
    private val meta: Metadata,
    private val settings: SettingsRepository,
    private val torrents: TorrentEngine,
    private val scope: CoroutineScope,
) {
    /** What every enabled addon answers for one video, then the rules' pick. */
    suspend fun bestStream(type: MediaType, stremioId: String, previous: SavedStream?): SavedStream? {
        val found = ArrayList<StreamOption>()
        withTimeoutOrNull(25_000) {
            addons.streams(type.stremio, stremioId).collect { if (it is AddonStreams.Loaded) found += it.streams }
        }
        return StreamRules.pick(found, settings.current, torrents.available, previous)?.saved
    }

    data class SeasonResult(val queued: Int, val missing: Int)

    /** Queues the released episodes of a season that aren't downloaded yet, in order. */
    suspend fun downloadSeason(details: Details, episodes: List<Episode>, previous: SavedStream?): SeasonResult {
        val imdb = details.imdbId ?: return SeasonResult(0, episodes.size)
        var last = previous
        var queued = 0
        var missing = 0
        for (ep in episodes.filter { it.released }.sortedBy { it.number }) {
            val key = ProgressEntry.key(MediaType.Tv, details.item.id, ep.season, ep.number)
            if (downloads.get(key)?.let { it.status != DownloadStatus.Failed } == true) continue
            val stream = bestStream(MediaType.Tv, "$imdb:${ep.season}:${ep.number}", last)
            if (stream == null) {
                missing++
                continue
            }
            last = stream
            downloads.add(
                DownloadItem(
                    id = key, type = MediaType.Tv, tmdbId = details.item.id, imdbId = imdb, season = ep.season, episode = ep.number,
                    title = details.item.title, episodeTitle = ep.name, poster = details.item.posterPath,
                    backdrop = details.item.backdropPath, still = ep.stillPath, stream = stream,
                ),
            )
            queued++
        }
        return SeasonResult(queued, missing)
    }

    /**
     * Smart downloads: [request] (an episode played from a download) was watched to the end.
     * The next episode downloads with the same release when it can, and this one is deleted.
     */
    fun episodeFinished(request: PlaybackRequest) {
        if (!settings.current.smartDownloads || !request.isLocal || request.type != MediaType.Tv) return
        val season = request.season ?: return
        val episode = request.episode ?: return
        val watched = downloads.get(request.progressKey) ?: return
        scope.launch {
            val imdb = request.imdbId
            val next = runCatching { nextEpisode(request.tmdbId, season, episode) }.getOrNull()
            if (imdb != null && next != null && next.released) {
                val key = ProgressEntry.key(MediaType.Tv, request.tmdbId, next.season, next.number)
                if (downloads.get(key) == null) {
                    bestStream(MediaType.Tv, "$imdb:${next.season}:${next.number}", watched.stream)?.let { stream ->
                        downloads.add(
                            watched.copy(
                                id = key, season = next.season, episode = next.number, episodeTitle = next.name, still = next.stillPath,
                                stream = stream, status = DownloadStatus.Queued, bytes = 0, total = 0, file = null, error = null,
                                subtitles = emptyList(),
                            ),
                        )
                    }
                }
            }
            // An open file can be deleted on Android: the player keeps reading it until it closes.
            downloads.remove(watched.id)
        }
    }

    private suspend fun nextEpisode(tmdbId: Int, season: Int, episode: Int): Episode? {
        meta.season(tmdbId, season).firstOrNull { it.number == episode + 1 }?.let { return it }
        val nextSeason = meta.details(MediaType.Tv, tmdbId).seasons.firstOrNull { it.number > season } ?: return null
        return meta.season(tmdbId, nextSeason.number).firstOrNull()
    }
}
