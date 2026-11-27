package com.synrg.harbor.stremio

import com.synrg.harbor.data.SavedStream
import com.synrg.harbor.data.Settings

/**
 * The automatic choice of a stream, used by "Watch now" (when "Play the best stream" is on),
 * season downloads and smart downloads. It keeps what the user allows in Settings (top quality,
 * largest file), never takes a CAM recording or something the phone can't play, and then prefers:
 * the same release as the previous episode (Stremio's bingeGroup), already cached on a debrid
 * service, the highest quality, enough seeds, and the smaller file. Ties keep the addons' order.
 */
object StreamRules {
    /** Seeds above this count as plenty: the rest of the order decides. */
    private const val ENOUGH_SEEDS = 30

    fun maxRank(settings: Settings): Int = when (settings.autoMaxQuality) {
        "4K" -> Quality.UHD.rank
        "720p" -> Quality.HD.rank
        else -> Quality.FHD.rank
    }

    fun allowed(option: StreamOption, settings: Settings, torrents: Boolean): Boolean {
        val maxBytes = settings.autoMaxSizeGb.takeIf { it > 0 }?.let { it * 1_073_741_824L }
        return when (option.kind) {
            StreamKind.Direct -> true
            StreamKind.Torrent -> torrents && (option.seeders ?: 1) > 0
            StreamKind.External -> false
        } && "CAM" !in option.tags &&
            option.quality.rank <= maxRank(settings) &&
            (maxBytes == null || option.sizeBytes == null || option.sizeBytes <= maxBytes)
    }

    fun pick(options: List<StreamOption>, settings: Settings, torrents: Boolean, previous: SavedStream? = null): StreamOption? {
        val ok = options.filter { allowed(it, settings, torrents) }
        if (ok.isEmpty()) return null
        previous?.bingeGroup?.let { group -> ok.firstOrNull { it.saved.bingeGroup == group }?.let { return it } }
        return ok.sortedWith(
            compareByDescending<StreamOption> { it.cached }
                .thenByDescending { it.quality.rank }
                .thenByDescending { if (it.kind == StreamKind.Torrent) minOf(it.seeders ?: 0, ENOUGH_SEEDS) else ENOUGH_SEEDS }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE },
        ).first()
    }
}
