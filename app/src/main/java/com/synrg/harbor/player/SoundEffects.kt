package com.synrg.harbor.player

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import com.synrg.harbor.data.Settings
import kotlin.math.log10

/**
 * Night mode, clearer dialogue and volume past 100%, with Android's DynamicsProcessing effect on
 * the player's audio session (so it works with every decoder, FFmpeg's too):
 * - night mode: a compressor brings loud scenes down and quiet ones up;
 * - dialogue: an EQ lifts the voice range (1–4 kHz) and lowers the rumble under 150 Hz;
 * - boost: gain before everything, with a limiter at the end so it doesn't clip.
 * Phones without the effect just play as before.
 */
class SoundEffects {
    private var effect: DynamicsProcessing? = null
    private var session = 0

    /** (Re)applies [settings] to [audioSession]; 0 or "nothing on" releases the effect. */
    fun apply(audioSession: Int, settings: Settings) {
        val on = settings.nightMode || settings.dialogueBoost || settings.volumeBoost > 100
        if (!on || audioSession == 0) {
            release()
            return
        }
        if (session != audioSession) release()
        session = audioSession
        runCatching {
            val config = config(settings)
            val current = effect
            if (current == null) {
                effect = DynamicsProcessing(0, audioSession, config).apply { enabled = true }
            } else {
                // Same session: swap the settings in place, no audible gap.
                for (ch in 0 until CHANNELS) {
                    current.setPreEqByChannelIndex(ch, config.getPreEqByChannelIndex(ch))
                    current.setMbcByChannelIndex(ch, config.getMbcByChannelIndex(ch))
                    current.setLimiterByChannelIndex(ch, config.getLimiterByChannelIndex(ch))
                    current.setInputGainbyChannel(ch, config.getInputGainByChannelIndex(ch))
                }
                current.enabled = true
            }
        }.onFailure {
            Log.w("SoundEffects", "DynamicsProcessing unavailable", it)
            release()
        }
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
        session = 0
    }

    private fun config(settings: Settings): DynamicsProcessing.Config {
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, EQ_EDGES.size,
            true, 1,
            false, 0,
            true,
        )
        val config = builder.build()

        val eq = DynamicsProcessing.Eq(true, settings.dialogueBoost, EQ_EDGES.size)
        EQ_EDGES.forEachIndexed { i, edge ->
            eq.setBand(i, DynamicsProcessing.EqBand(settings.dialogueBoost, edge, if (settings.dialogueBoost) DIALOGUE_GAINS[i] else 0f))
        }
        config.setPreEqAllChannelsTo(eq)

        val mbc = DynamicsProcessing.Mbc(true, settings.nightMode, 1)
        mbc.setBand(
            0,
            DynamicsProcessing.MbcBand(
                settings.nightMode, 20_000f,
                10f, 250f, // attack, release (ms)
                4f, -32f, // ratio, threshold (dB)
                6f, // knee
                -90f, 1f, // noise gate threshold, expander ratio (off)
                0f, if (settings.nightMode) 8f else 0f, // pre-gain, post-gain: quiet parts come up
            ),
        )
        config.setMbcAllChannelsTo(mbc)

        val boostDb = if (settings.volumeBoost > 100) 20f * log10(settings.volumeBoost / 100f) else 0f
        config.setInputGainAllChannelsTo(boostDb)
        config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f))
        return config
    }

    private companion object {
        /** Session effects run on the mixer's output, stereo on phones. */
        const val CHANNELS = 2
        /** Upper edge of each EQ band (Hz) and its gain (dB) for clearer dialogue. */
        val EQ_EDGES = floatArrayOf(150f, 1_000f, 4_000f, 20_000f).toList()
        val DIALOGUE_GAINS = floatArrayOf(-4f, 0f, 5f, 1.5f)
    }
}
