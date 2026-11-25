package com.synrg.harbor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.graph
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.player.PlayerActivity
import com.synrg.harbor.ui.detail.DetailScreen
import com.synrg.harbor.ui.detail.DetailViewModel
import com.synrg.harbor.ui.home.HomeScreen
import com.synrg.harbor.ui.home.HomeViewModel
import com.synrg.harbor.ui.library.LibraryScreen
import com.synrg.harbor.ui.search.SearchScreen
import com.synrg.harbor.ui.search.SearchViewModel
import com.synrg.harbor.ui.settings.SettingsScreen
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object SearchRoute
@Serializable object LibraryRoute
@Serializable object SettingsRoute
@Serializable data class DetailRoute(val type: String, val id: Int, val autoplay: Boolean = false)

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, "Inicio", HIcons.Home),
    Tab(SearchRoute, "Buscar", HIcons.Search),
    Tab(LibraryRoute, "Mi lista", HIcons.Bookmark),
    Tab(SettingsRoute, "Ajustes", HIcons.Settings),
)

private val BarHeight = 64.dp

@Composable
fun HarborRoot() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val graph = context.graph
    val hazeState = remember { HazeState() }
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = destination == null || tabs.any { t -> destination.hasRoute(t.route::class) }
    val bottomPadding = BarHeight + 28.dp

    fun open(item: MediaItem, autoplay: Boolean = false) {
        graph.previews["${item.type.tmdb}:${item.id}"] = item
        nav.navigate(DetailRoute(item.type.tmdb, item.id, autoplay))
    }
    fun resume(entry: ProgressEntry) = open(
        MediaItem(entry.tmdbId, entry.type, entry.title, posterPath = entry.poster, backdropPath = entry.backdrop),
        autoplay = true,
    )
    fun play(request: PlaybackRequest) = context.startActivity(PlayerActivity.intent(context, request, graph.json))

    Box(Modifier.fillMaxSize().background(HarborColors.Bg)) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(160)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 0.985f) },
        ) {
            composable<HomeRoute> {
                val vm: HomeViewModel = viewModel { HomeViewModel(graph) }
                HomeScreen(
                    vm, bottomPadding,
                    onOpen = { open(it) },
                    onPlay = { open(it, autoplay = true) },
                    onResume = ::resume,
                )
            }
            composable<SearchRoute> {
                val vm: SearchViewModel = viewModel { SearchViewModel(graph) }
                SearchScreen(vm, bottomPadding, onOpen = { open(it) })
            }
            composable<LibraryRoute> {
                LibraryScreen(bottomPadding, onOpen = { open(it) }, onResume = ::resume)
            }
            composable<SettingsRoute> {
                SettingsScreen(bottomPadding)
            }
            composable<DetailRoute> { backStack ->
                val route = backStack.toRoute<DetailRoute>()
                val type = MediaType.fromTmdb(route.type) ?: MediaType.Movie
                val vm: DetailViewModel = viewModel { DetailViewModel(graph, type, route.id, route.autoplay) }
                DetailScreen(vm, onBack = { nav.popBackStack() }, onOpen = { open(it) }, onPlay = ::play)
            }
        }

        AnimatedVisibility(
            visible = showBar,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(nav, hazeState, destination)
        }
    }
}

private fun switchTab(nav: NavHostController, route: Any) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun BottomBar(nav: NavHostController, hazeState: HazeState, destination: androidx.navigation.NavDestination?) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .height(BarHeight)
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = HarborColors.Bg,
                    tint = HazeTint(Color(0xB3101318)),
                    blurRadius = 24.dp,
                    noiseFactor = 0.04f,
                ),
            ) {
                // Blur at half resolution: about a quarter of the GPU work, same look on a dark bar.
                inputScale = HazeInputScale.Fixed(0.5f)
            }
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val selected = destination?.hasRoute(tab.route::class) == true
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { if (!selected) switchTab(nav, tab.route) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (selected) HarborColors.Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 3.dp),
                ) {
                    Icon(tab.icon, tab.label, tint = if (selected) HarborColors.Accent else HarborColors.TextDim, modifier = Modifier.size(22.dp))
                }
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) HarborColors.Text else HarborColors.TextFaint,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
