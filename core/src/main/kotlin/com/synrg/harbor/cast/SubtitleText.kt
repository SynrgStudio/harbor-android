package com.synrg.harbor.cast

import com.synrg.harbor.player.SubtitleCue

/** Subtitles written back out for TVs: WebVTT for Chromecast, SRT for DLNA. */
object SubtitleText {
    private fun stamp(ms: Long, separator: Char): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        return "%02d:%02d:%02d%c%03d".format(h, m, s, separator, ms % 1000)
    }

    fun vtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { append(stamp(it.startMs, '.')).append(" --> ").append(stamp(it.endMs, '.')).append('\n').append(it.text).append("\n\n") }
    }

    fun srt(cues: List<SubtitleCue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(stamp(c.startMs, ',')).append(" --> ").append(stamp(c.endMs, ',')).append('\n').append(c.text).append("\n\n")
        }
    }
}
