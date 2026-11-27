package com.synrg.harbor.player

import androidx.compose.runtime.Immutable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One subtitle line on screen from [startMs] to [endMs]. */
@Immutable
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Reads SRT, WebVTT and ASS/SSA files into plain cues, for the second subtitle (drawn by the app,
 * since Media3 shows one text track at a time). Styling tags are dropped.
 */
object SubtitleFile {
    private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val ARROW = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
    // Braces escaped: Android's regex engine (ICU) rejects a bare "}", the JVM's doesn't.
    private val TAGS = Regex("""<[^>]+>|\{[^}]*\}""")

    /** UTF-8 when it decodes cleanly, else Windows-1252 (older subtitle files). */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("\uFEFF")
    }

    fun parse(text: String): List<SubtitleCue> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val cues = if ("[Events]" in normalized || normalized.lineSequence().any { it.startsWith("Dialogue:") }) {
            parseAss(normalized)
        } else {
            parseTimed(normalized)
        }
        return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    /** SRT and WebVTT: a "start --> end" line, then the text up to a blank line. */
    private fun parseTimed(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val m = ARROW.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val start = time(m.groupValues[1])
            val end = time(m.groupValues[2])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            if (start != null && end != null) cues += SubtitleCue(start, end, clean(body.toString()))
        }
        return cues
    }

    /** ASS/SSA: "Dialogue:" lines, fields as named by the [Events] "Format:" line. */
    private fun parseAss(text: String): List<SubtitleCue> {
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        val cues = ArrayList<SubtitleCue>()
        var inEvents = false
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) inEvents = trimmed.equals("[Events]", ignoreCase = true)
            if (!inEvents) continue
            if (trimmed.startsWith("Format:")) {
                fields = trimmed.removePrefix("Format:").split(',').map { it.trim() }
                continue
            }
            if (!trimmed.startsWith("Dialogue:")) continue
            val values = trimmed.removePrefix("Dialogue:").split(',', limit = fields.size)
            fun field(name: String) = fields.indexOf(name).takeIf { it >= 0 }?.let { values.getOrNull(it)?.trim() }
            val start = field("Start")?.let(::time) ?: continue
            val end = field("End")?.let(::time) ?: continue
            val body = field("Text")?.replace("\\N", "\n")?.replace("\\n", "\n")?.replace("\\h", " ") ?: continue
            cues += SubtitleCue(start, end, clean(body))
        }
        return cues
    }

    private fun clean(text: String): String = text.replace(TAGS, "").lines().joinToString("\n") { it.trim() }.trim()

    private fun time(value: String): Long? {
        val m = TIME.find(value) ?: return null
        val (h, min, s, frac) = m.destructured
        val ms = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return ((h.toLongOrNull() ?: 0) * 3600 + min.toLong() * 60 + s.toLong()) * 1000 + ms
    }

    /** The text on screen at [positionMs] (several overlapping cues are stacked), or null. */
    fun at(cues: List<SubtitleCue>, positionMs: Long): String? {
        if (cues.isEmpty()) return null
        // The last cue that starts at or before the position, then look back for overlaps.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return null
        val showing = ArrayList<String>(2)
        var i = last
        while (i >= 0 && last - i < 8) {
            val cue = cues[i]
            if (positionMs < cue.endMs) showing += cue.text
            i--
        }
        return showing.asReversed().joinToString("\n").ifEmpty { null }
    }
}
