package com.synrg.harbor.player

import com.synrg.harbor.data.IntroSkip

/**
 * Learns a series' intro from the user skipping it by hand: a jump forward of 20 s to 3 min in
 * the first 10 minutes of an episode. Several jumps in a row (double taps, a swipe and a correction)
 * count as one when each starts where the previous one landed, within [MERGE_MS].
 * Later episodes then offer "Skip intro", which jumps that same length.
 */
class IntroLearner(private val onLearned: (IntroSkip) -> Unit) {
    private var origin = -1L
    private var target = 0L
    private var lastAt = 0L

    /** A seek from [fromMs] to [toMs] at [nowMs] (elapsed realtime). */
    fun onSeek(fromMs: Long, toMs: Long, nowMs: Long) {
        val continues = origin >= 0 && nowMs - lastAt < MERGE_MS && kotlin.math.abs(fromMs - target) < MERGE_MS
        if (!continues) {
            finish()
            origin = fromMs
        }
        target = toMs
        lastAt = nowMs
    }

    /** Called a few seconds after the last seek (and when the video changes): decides. */
    fun finish() {
        if (origin < 0) return
        val jump = target - origin
        if (origin < MAX_START_MS && jump in MIN_JUMP_MS..MAX_JUMP_MS) onLearned(IntroSkip(origin, jump))
        origin = -1
    }

    companion object {
        const val MERGE_MS = 4_000L
        const val MAX_START_MS = 10 * 60_000L
        const val MIN_JUMP_MS = 20_000L
        const val MAX_JUMP_MS = 180_000L

        /** Where the button shows: from a while before the intro usually starts to a minute after it ends. */
        fun window(intro: IntroSkip): LongRange =
            (intro.startMs - 150_000L).coerceAtLeast(0L)..(intro.startMs + intro.lengthMs + 60_000L)
    }
}
