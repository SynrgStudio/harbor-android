package com.synrg.harbor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.synrg.harbor.R
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors

val PosterShape = RoundedCornerShape(10.dp)

/** Slight shrink while pressed: cheap (graphicsLayer only) and makes taps feel physical. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 900f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 112.dp) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .pressScale(source)
            .clip(PosterShape)
            .background(HarborColors.SurfaceHigh)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        // The name instead of the poster when there is none, or it fails to load (only then: rows stay cheap).
        var failed by remember(item.posterPath) { mutableStateOf(false) }
        if (item.posterPath != null && !failed) {
            AsyncImage(
                model = Img.poster(item.posterPath),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                color = HarborColors.TextDim,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxSize().border(1.dp, HarborColors.Outline, PosterShape))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = HarborColors.Text,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun PosterRow(title: String, items: List<MediaItem>, onOpen: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 22.dp)) {
        SectionTitle(title)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
                PosterCard(item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
fun PosterRowPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 22.dp)) {
        Box(
            Modifier.padding(horizontal = 16.dp).width(140.dp).height(16.dp)
                .clip(RoundedCornerShape(6.dp)).background(HarborColors.SurfaceHigh),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) {
                Box(Modifier.width(112.dp).aspectRatio(2f / 3f).clip(PosterShape).background(HarborColors.Surface))
            }
        }
    }
}

@Composable
fun ContinueCard(entry: ProgressEntry, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(250.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .pressScale(source)
                .clip(RoundedCornerShape(12.dp))
                .background(HarborColors.SurfaceHigh)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
        ) {
            AsyncImage(
                model = Img.still(entry.still, "w500") ?: Img.backdrop(entry.backdrop, "w780"),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000)),
                ),
            )
            Box(
                Modifier.align(Alignment.Center).size(42.dp).clip(CircleShape)
                    .background(Color(0x66000000)).border(1.dp, Color(0x55FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HIcons.Play, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(
                if (entry.remainingMin > 0) stringResource(R.string.home_minutes_left, entry.remainingMin) else stringResource(R.string.home_almost_done),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp),
            )
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color(0x33FFFFFF))
                    .drawBehind {
                        drawRect(HarborColors.Accent, size = size.copy(width = size.width * entry.fraction))
                    },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val seasonNo = entry.season
        val episodeNo = entry.episode
        if (seasonNo != null && episodeNo != null) {
            Text(
                stringResource(R.string.common_episode_code, seasonNo, episodeNo) + (entry.episodeTitle?.let { "  $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = HarborColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(46.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(23.dp))
            .background(if (enabled) HarborColors.Accent else HarborColors.SurfaceHighest)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = HarborColors.OnAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) HarborColors.OnAccent else HarborColors.TextDim, maxLines = 1)
    }
}

@Composable
fun GlassButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(46.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(23.dp))
            .background(Color(0x26FFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(23.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Color.White) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(source)
            .clip(CircleShape)
            .background(Color(0x59000000))
            .border(1.dp, Color(0x1AFFFFFF), CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun RatingText(rating: Double, modifier: Modifier = Modifier) {
    if (rating <= 0) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(HIcons.Star, null, tint = HarborColors.Gold, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(String.format(java.util.Locale.ROOT, "%.1f", rating), style = MaterialTheme.typography.labelMedium, color = HarborColors.Text)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = HarborColors.SurfaceHighest, textColor: Color = HarborColors.Text) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
        color = textColor,
        maxLines = 1,
    )
}

@Composable
fun MessageBlock(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = HarborColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextDim)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
