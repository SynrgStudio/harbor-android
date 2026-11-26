package com.synrg.harbor.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput

/** How much later than the video subtitles show (negative: earlier). Read on the playback thread. */
class SubtitleTiming {
    @Volatile var delayUs: Long = 0
}

/**
 * Media3 has no subtitle delay and its TextRenderer is final, so this wraps it: the renderer
 * picks its cues as if playback were [SubtitleTiming.delayUs] behind. Samples arrive ahead of
 * playback, so showing subtitles earlier works too.
 */
private class DelayedTextRenderer(private val inner: Renderer, private val timing: SubtitleTiming) : Renderer by inner {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = inner.render(positionUs - timing.delayUs, elapsedRealtimeUs)

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long) =
        inner.getDurationToProgressUs(positionUs - timing.delayUs, elapsedRealtimeUs)

    // Java default methods: forwarded explicitly so the wrapper never falls back to the defaults.
    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long) = inner.supportsResetPositionWithoutKeyFrameReset(positionUs)
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) = inner.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    override fun enableMayRenderStartOfStream() = inner.enableMayRenderStartOfStream()
    override fun release() = inner.release()
}

/** The usual renderers, with the subtitle one following [timing]. */
class HarborRenderersFactory(context: Context, private val timing: SubtitleTiming) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { DelayedTextRenderer(it, timing) }
    }
}
