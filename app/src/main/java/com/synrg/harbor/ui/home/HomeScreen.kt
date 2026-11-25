package com.synrg.harbor.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.synrg.harbor.data.HeroItem
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.ui.components.ContinueCard
import com.synrg.harbor.ui.components.GlassButton
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.PosterRow
import com.synrg.harbor.ui.components.PosterRowPlaceholder
import com.synrg.harbor.ui.components.PrimaryButton
import com.synrg.harbor.ui.components.SectionTitle
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    bottomPadding: Dp,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onResume: (ProgressEntry) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(HarborColors.Bg)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "hero", contentType = "hero") {
                Hero(state.hero, onOpen = onOpen, onPlay = onPlay)
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "continue", contentType = "continue") {
                    Column(Modifier.padding(top = 18.dp)) {
                        SectionTitle("Seguir viendo")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(continueWatching, key = { it.key }, contentType = { "cw" }) { entry ->
                                ContinueCard(entry, onClick = { onResume(entry) })
                            }
                        }
                    }
                }
            }
            if (state.error != null && state.rows.all { it == null } && !state.loading) {
                item(key = "error") {
                    MessageBlock("No se pudo cargar", state.error ?: "", action = { PrimaryButton("Reintentar", HIcons.Refresh, vm::load) })
                }
            }
            itemsIndexed(state.rows, key = { i, row -> row?.key ?: "placeholder-$i" }, contentType = { _, row -> if (row == null) "placeholder" else "row" }) { _, row ->
                when {
                    row == null -> if (state.loading) PosterRowPlaceholder()
                    row.items.isNotEmpty() -> PosterRow(row.title, row.items, onOpen)
                }
            }
        }
        // derivedStateOf: recompose the bar only when it flips, not on every scrolled pixel.
        val solid by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
        TopBar(solid)
    }
}

@Composable
private fun TopBar(solid: Boolean) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (solid) 0.92f else 0f, tween(220), label = "bar")
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to HarborColors.Bg.copy(alpha = 0.55f + alpha * 0.4f),
                    1f to HarborColors.Bg.copy(alpha = alpha * 0.85f),
                ),
            ),
    ) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 20.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Harbor", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(HarborColors.Accent))
        }
    }
}

@Composable
private fun Hero(items: List<HeroItem>, onOpen: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * 1.32f).coerceAtMost(640.dp)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(height).background(HarborColors.Surface))
            return@BoxWithConstraints
        }
        val pager = rememberPagerState { items.size }
        AutoAdvance(pager)
        Box(Modifier.fillMaxWidth().height(height)) {
            HorizontalPager(pager, beyondViewportPageCount = 1, key = { items[it].item.id }) { page ->
                val hero = items[page]
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    AsyncImage(
                        model = Img.backdrop(hero.item.backdropPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // Gentle parallax while swiping.
                                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                                translationX = offset * size.width * 0.35f
                            },
                    )
                }
            }
            // Scrims: top for the status bar, bottom fading into the page.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x8C000000),
                        0.18f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.82f to HarborColors.Bg.copy(alpha = 0.86f),
                        1f to HarborColors.Bg,
                    ),
                ),
            )
            val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    contentKey = { it.item.id },
                    label = "hero-info",
                ) { hero ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (hero.logoPath != null) {
                            AsyncImage(
                                model = Img.logo(hero.logoPath),
                                contentDescription = hero.item.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 104.dp).fillMaxWidth().height(104.dp),
                            )
                        } else {
                            Text(
                                hero.item.title,
                                style = MaterialTheme.typography.displaySmall,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            listOfNotNull(
                                if (hero.item.type == MediaType.Movie) "Película" else "Serie",
                                hero.item.year,
                                *hero.genres.toTypedArray(),
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.TextDim,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Ver", HIcons.Play, onClick = { onPlay(current.item) }, modifier = Modifier.width(132.dp))
                    GlassButton("Detalles", HIcons.Info, onClick = { onOpen(current.item) }, modifier = Modifier.width(132.dp))
                }
                Spacer(Modifier.height(16.dp))
                PagerDots(items.size, pager.currentPage)
            }
        }
    }
}

@Composable
private fun AutoAdvance(pager: PagerState) {
    val dragging by remember { derivedStateOf { pager.isScrollInProgress } }
    LaunchedEffect(pager.settledPage, dragging, pager.pageCount) {
        if (dragging || pager.pageCount < 2) return@LaunchedEffect
        delay(7_000)
        pager.animateScrollToPage((pager.settledPage + 1) % pager.pageCount, animationSpec = tween(700))
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 18.dp else 6.dp, tween(250), label = "dot")
            Box(
                Modifier.height(6.dp).width(width).clip(RoundedCornerShape(3.dp))
                    .background(if (i == current) HarborColors.Accent else Color(0x55FFFFFF)),
            )
        }
    }
}

@Suppress("unused")
private fun pageOffset(pager: PagerState, page: Int) = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
