package com.synrg.harbor.stremio

import androidx.compose.runtime.Immutable
import com.synrg.harbor.core.CoreStrings
import com.synrg.harbor.core.CoreText
import com.synrg.harbor.data.SavedStream
import java.util.Locale

enum class Quality(val label: String, val rank: Int) {
    UHD("4K", 4), FHD("1080p", 3), HD("720p", 2), SD("SD", 1), Unknown("", 0)
}

enum class StreamKind { Direct, Torrent, External }

/** A Stremio stream with the free-text bits (quality, size, seeds...) parsed into fields. */
@Immutable
data class StreamOption(
    val key: String,
    val addonName: String,
    val title: String,
    val quality: Quality,
    val tags: List<String>,
    val sizeLabel: String?,
    /** The file's size when the addon says it (exactly, or in its text). */
    val sizeBytes: Long?,
    val seeders: Int?,
    val source: String?,
    val languages: List<String>,
    val cached: Boolean,
    val kind: StreamKind,
    val saved: SavedStream,
    val externalUrl: String?,
) {
    val playable: Boolean get() = kind != StreamKind.External
}

object StreamParser {
    private val uhd = Regex("""(?i)\b(2160p|4k|uhd)\b""")
    private val fhd = Regex("""(?i)\b1080p\b""")
    private val hd = Regex("""(?i)\b720p\b""")
    private val sd = Regex("""(?i)\b(576p|480p|360p|sd|dvdrip)\b""")
    private val size = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(TB|GB|MB)\b""")
    private val seeds = Regex("""👤\s*(\d+)""")
    private val source = Regex("""⚙️?\s*([^\n]+)""")
    private val flag = Regex("""[\x{1F1E6}-\x{1F1FF}]{2}""")
    private val cachedTag = Regex("""(?i)\[(RD|AD|PM|TB|DL|ED|OC|PD|EN)\+]|⚡""")
    private val emojiLine = Regex("""^[👤💾⚙️🌐🔗📦⏳🎞️🔊🗣️]""")

    private val tagRules = listOf(
        "DV" to Regex("""(?i)\b(DV|DoVi|dolby[ ._-]?vision)\b"""),
        "HDR" to Regex("""(?i)\bHDR(10\+?)?\b"""),
        "HEVC" to Regex("""(?i)\b(x[ ._]?265|hevc|h[ ._]?265)\b"""),
        "AV1" to Regex("""(?i)\bAV1\b"""),
        "10bit" to Regex("""(?i)\b10[ ._-]?bit\b"""),
        "Atmos" to Regex("""(?i)\batmos\b"""),
        "REMUX" to Regex("""(?i)\bremux\b"""),
        "BluRay" to Regex("""(?i)\b(blu[ ._-]?ray|bdrip|brrip)\b"""),
        "WEB" to Regex("""(?i)\bweb[ ._-]?(dl|rip)?\b"""),
        "Multi" to Regex("""(?i)\b(multi|dual)[ ._-]?(audio)?\b"""),
        "CAM" to Regex("""(?i)\b(cam|hdcam|telesync|hdts)\b"""),
    )

    fun parse(addonName: String, index: Int, s: StremioStream): StreamOption? {
        val kind = when {
            !s.url.isNullOrBlank() -> StreamKind.Direct
            !s.infoHash.isNullOrBlank() -> StreamKind.Torrent
            !s.externalUrl.isNullOrBlank() -> StreamKind.External
            else -> return null
        }
        val body = s.description ?: s.title ?: ""
        val all = listOfNotNull(s.name, body, s.behaviorHints?.filename).joinToString("\n")
        val quality = when {
            uhd.containsMatchIn(all) -> Quality.UHD
            fhd.containsMatchIn(all) -> Quality.FHD
            hd.containsMatchIn(all) -> Quality.HD
            sd.containsMatchIn(all) -> Quality.SD
            else -> Quality.Unknown
        }
        val bodyLines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = s.behaviorHints?.filename
            ?: bodyLines.firstOrNull { !emojiLine.containsMatchIn(it) }
            ?: s.name?.lines()?.joinToString(" ")?.trim()
            ?: CoreStrings.get(CoreText.StreamsFallbackTitle, index + 1)
        val sizeLabel = s.behaviorHints?.videoSize?.takeIf { it > 0 }?.let(::formatBytes)
            ?: size.find(body)?.let { "${it.groupValues[1].replace(',', '.')} ${it.groupValues[2].uppercase(Locale.ROOT)}" }
        val sizeBytes = s.behaviorHints?.videoSize?.takeIf { it > 0 } ?: size.find(body)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            val unit = when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "TB" -> 1_099_511_627_776.0
                "GB" -> 1_073_741_824.0
                else -> 1_048_576.0
            }
            (n * unit).toLong()
        }
        val sourceName = source.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..40 }
        val trackers = s.sources.orEmpty()
            .filter { it.startsWith("tracker:") }
            .map { it.removePrefix("tracker:") }
        val saved = SavedStream(
            url = s.url,
            infoHash = s.infoHash?.lowercase(Locale.ROOT),
            fileIdx = s.fileIdx,
            trackers = trackers,
            filename = s.behaviorHints?.filename,
            headers = s.behaviorHints?.proxyHeaders?.request.orEmpty(),
            addonName = addonName,
            label = listOf(quality.label, sizeLabel).filter { !it.isNullOrBlank() }.joinToString(" · "),
            bingeGroup = s.behaviorHints?.bingeGroup,
        )
        return StreamOption(
            key = "$addonName#$index#${s.infoHash ?: s.url ?: s.externalUrl}",
            addonName = addonName,
            title = title,
            quality = quality,
            tags = tagRules.filter { (_, r) -> r.containsMatchIn(all) }.map { it.first }.distinct(),
            sizeLabel = sizeLabel,
            sizeBytes = sizeBytes,
            seeders = seeds.find(body)?.groupValues?.get(1)?.toIntOrNull(),
            source = sourceName,
            languages = flag.findAll(body).map { it.value }.distinct().take(6).toList(),
            cached = cachedTag.containsMatchIn(s.name.orEmpty()),
            kind = kind,
            saved = saved,
            externalUrl = s.externalUrl,
        )
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.ROOT, "%.1f GB", gb)
        else String.format(Locale.ROOT, "%.0f MB", bytes / 1_048_576.0)
    }
}
