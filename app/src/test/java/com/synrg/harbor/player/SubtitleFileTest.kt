package com.synrg.harbor.player

import com.synrg.harbor.data.IntroSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFileTest {
    @Test
    fun readsSrt() {
        val cues = SubtitleFile.parse(
            """
            1
            00:00:01,500 --> 00:00:03,000
            <i>Hello</i> there

            2
            00:01:02,000 --> 00:01:04,250
            Two
            lines
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1_500, 3_000, "Hello there"), cues[0])
        assertEquals("Two\nlines", cues[1].text)
        assertEquals(64_250, cues[1].endMs)
        assertEquals("Hello there", SubtitleFile.at(cues, 2_000))
        assertNull(SubtitleFile.at(cues, 3_500))
    }

    @Test
    fun readsVttWithoutHours() {
        val cues = SubtitleFile.parse("WEBVTT\n\n00:05.000 --> 00:07.000 align:start\nHi\n")
        assertEquals(listOf(SubtitleCue(5_000, 7_000, "Hi")), cues)
    }

    @Test
    fun readsAss() {
        val cues = SubtitleFile.parse(
            """
            [Script Info]
            Title: x

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.50,0:00:04.00,Default,,0,0,0,,{\an8}Top, with a comma\NSecond line
            """.trimIndent(),
        )
        assertEquals(listOf(SubtitleCue(2_500, 4_000, "Top, with a comma\nSecond line")), cues)
    }

    @Test
    fun stacksOverlappingCues() {
        val cues = listOf(SubtitleCue(0, 10_000, "A"), SubtitleCue(2_000, 4_000, "B"))
        assertEquals("A\nB", SubtitleFile.at(cues, 3_000))
        assertEquals("A", SubtitleFile.at(cues, 5_000))
    }

    @Test
    fun decodesLatin1() {
        val bytes = "Canción".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Canción", SubtitleFile.decode(bytes))
    }

    @Test
    fun learnsAnIntroFromSeveralSkips() {
        var learned: IntroSkip? = null
        val learner = IntroLearner { learned = it }
        // Three double taps and a swipe, each starting where the last one landed.
        learner.onSeek(60_000, 70_000, 1_000)
        learner.onSeek(70_000, 80_000, 1_400)
        learner.onSeek(80_500, 90_000, 2_000)
        learner.onSeek(90_200, 145_000, 5_000)
        learner.finish()
        assertEquals(IntroSkip(60_000, 85_000), learned)
    }

    @Test
    fun ignoresShortOrLateJumps() {
        var learned: IntroSkip? = null
        val learner = IntroLearner { learned = it }
        learner.onSeek(60_000, 70_000, 1_000) // 10 s: a double tap, not an intro
        learner.finish()
        learner.onSeek(20 * 60_000L, 21 * 60_000L, 30_000) // a minute, but at 20 minutes
        learner.finish()
        assertNull(learned)
    }
}
