package com.synrg.harbor.player

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.synrg.harbor.data.Settings

/**
 * How subtitles look, from Settings: text color, edge (outline, shadow or a dark box), font and
 * height on screen. The same style draws Media3's subtitles, the second subtitle and the preview
 * in Settings.
 */
object SubtitleStyle {
    val colors = listOf("white", "yellow", "cyan")
    val edges = listOf("outline", "shadow", "box")
    val fonts = listOf("medium", "bold", "serif")
    val positions = listOf("low", "normal", "high")

    fun color(settings: Settings): Int = when (settings.subtitleColor) {
        "yellow" -> 0xFFFFE45C.toInt()
        "cyan" -> 0xFF7DE3FF.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    fun typeface(settings: Settings): Typeface = when (settings.subtitleFont) {
        "bold" -> Typeface.create("sans-serif", Typeface.BOLD)
        "serif" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Space under the subtitles, as a fraction of the picture's height. */
    fun bottomFraction(settings: Settings): Float = when (settings.subtitlePosition) {
        "low" -> 0.025f
        "high" -> 0.16f
        else -> 0.06f
    }

    fun caption(settings: Settings): CaptionStyleCompat {
        val box = settings.subtitleEdge == "box"
        return CaptionStyleCompat(
            color(settings),
            if (box) 0xB3000000.toInt() else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            when (settings.subtitleEdge) {
                "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                "box" -> CaptionStyleCompat.EDGE_TYPE_NONE
                else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            },
            android.graphics.Color.BLACK,
            typeface(settings),
        )
    }

    fun apply(view: SubtitleView, settings: Settings) {
        // Embedded colors and positions stay (signs in anime); only their font sizes are ignored.
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(caption(settings))
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.12f * settings.subtitleScale)
        view.setBottomPaddingFraction(bottomFraction(settings))
    }
}

/** A subtitle line drawn in Compose with the user's style: the second subtitle, and the Settings preview. */
@Composable
fun StyledSubtitle(text: String, settings: Settings, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val color = Color(SubtitleStyle.color(settings))
    val family = when (settings.subtitleFont) {
        "serif" -> FontFamily.Serif
        else -> FontFamily.SansSerif
    }
    val weight = when (settings.subtitleFont) {
        "bold" -> FontWeight.Bold
        "serif" -> FontWeight.Normal
        else -> FontWeight.Medium
    }
    val base = TextStyle(fontSize = fontSize, fontFamily = family, fontWeight = weight, textAlign = TextAlign.Center, lineHeight = fontSize * 1.2f)
    when (settings.subtitleEdge) {
        "box" -> Text(
            text,
            style = base.copy(color = color),
            modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        "shadow" -> Text(
            text,
            style = base.copy(color = color, shadow = Shadow(Color.Black, Offset(3f, 3f), blurRadius = 4f)),
            modifier = modifier,
        )
        else -> Box(modifier) {
            // An outline: the same text stroked in black under the filled one.
            Text(text, style = base.copy(color = Color.Black, drawStyle = Stroke(width = 6f, join = StrokeJoin.Round)))
            Text(text, style = base.copy(color = color))
        }
    }
}
