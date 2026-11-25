package com.synrg.harbor.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.synrg.harbor.R

object HarborColors {
    val Bg = Color(0xFF07080B)
    val Surface = Color(0xFF111318)
    val SurfaceHigh = Color(0xFF1A1D24)
    val SurfaceHighest = Color(0xFF242832)
    val Outline = Color(0x1FFFFFFF)
    val Accent = Color(0xFF7DD3FC)
    val AccentDeep = Color(0xFF38BDF8)
    val OnAccent = Color(0xFF04131C)
    val Text = Color(0xFFF4F6FA)
    val TextDim = Color(0xFFA6AEBB)
    val TextFaint = Color(0xFF6B7380)
    val Gold = Color(0xFFFACC15)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFB923C)
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

private fun style(size: Int, weight: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private val HarborTypography = Typography(
    displaySmall = style(34, 800, 40, -0.8),
    headlineMedium = style(28, 800, 34, -0.6),
    headlineSmall = style(22, 700, 28, -0.3),
    titleLarge = style(19, 700, 24, -0.2),
    titleMedium = style(16, 600, 22, -0.1),
    titleSmall = style(14, 600, 20),
    bodyLarge = style(15, 400, 22),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(12, 400, 16),
    labelLarge = style(14, 600, 18),
    labelMedium = style(12, 600, 16, 0.1),
    labelSmall = style(11, 600, 14, 0.2),
)

private val HarborScheme = darkColorScheme(
    primary = HarborColors.Accent,
    onPrimary = HarborColors.OnAccent,
    primaryContainer = Color(0xFF0C3A52),
    onPrimaryContainer = HarborColors.Accent,
    secondary = HarborColors.TextDim,
    background = HarborColors.Bg,
    onBackground = HarborColors.Text,
    surface = HarborColors.Surface,
    onSurface = HarborColors.Text,
    surfaceVariant = HarborColors.SurfaceHigh,
    onSurfaceVariant = HarborColors.TextDim,
    surfaceContainerLowest = HarborColors.Bg,
    surfaceContainerLow = HarborColors.Surface,
    surfaceContainer = HarborColors.Surface,
    surfaceContainerHigh = HarborColors.SurfaceHigh,
    surfaceContainerHighest = HarborColors.SurfaceHighest,
    outline = Color(0x33FFFFFF),
    outlineVariant = HarborColors.Outline,
    error = Color(0xFFF87171),
)

@Composable
fun HarborTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HarborScheme, typography = HarborTypography) {
        // No Surface at the root, so set the default text/icon color here.
        CompositionLocalProvider(LocalContentColor provides HarborColors.Text, content = content)
    }
}
