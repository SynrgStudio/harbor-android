package com.synrg.harbor.ui

import androidx.annotation.StringRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.synrg.harbor.R
import com.synrg.harbor.core.AppLink
import kotlinx.coroutines.flow.MutableStateFlow
import com.synrg.harbor.ui.downloads.DownloadsScreen
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.MediaType
import com.synrg.harbor.data.ProgressEntry
import com.synrg.harbor.graph
import com.synrg.harbor.player.PlaybackRequest
import com.synrg.harbor.player.PlayerActivity
import com.synrg.harbor.ui.continuewatching.ContinueScreen
import com.synrg.harbor.ui.detail.DetailScreen
import com.synrg.harbor.ui.detail.DetailViewModel
import com.synrg.harbor.ui.home.HomeScreen
import com.synrg.harbor.ui.home.HomeViewModel
import com.synrg.harbor.ui.library.LibraryScreen
import com.synrg.harbor.ui.person.PersonScreen
import com.synrg.harbor.ui.person.PersonViewModel
import com.synrg.harbor.ui.search.SearchScreen
import com.synrg.harbor.ui.search.SearchViewModel
import com.synrg.harbor.ui.settings.SettingsScreen
import com.synrg.harbor.ui.stats.StatsScreen
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
@Serializable object ContinueRoute
@Serializable object SearchRoute
@Serializable object LibraryRoute
@Serializable object SettingsRoute
@Serializable object DownloadsRoute
@Serializable object StatsRoute
/** [season]: the one to show first (a new episode's), instead of the one being watched. */
@Serializable data class DetailRoute(val type: String, val id: Int, val autoplay: Boolean = false, val season: Int? = null)
/** [titleImdb]: the title the person was opened from, to tell apart people with the same name. */
@Serializable data class PersonRoute(val name: String, val personId: Int? = null, val photo: String? = null, val titleImdb: String? = null)

private data class Tab(val route: Any, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, HIcons.Home),
    Tab(ContinueRoute, R.string.nav_continue, HIcons.History),
    Tab(SearchRoute, R.string.nav_search, HIcons.Search),
    Tab(LibraryRoute, R.string.nav_library, HIcons.Bookmark),
    Tab(SettingsRoute, R.string.nav_settings, HIcons.Settings),
)

private val BarHeight = 64.dp

@Composable
fun HarborRoot(links: MutableStateFlow<AppLink?>) {
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
    fun play(request: PlaybackRequest) = context.startActivity(PlayerActivity.intent(context, request, graph.json))
    fun details(entry: ProgressEntry, autoplay: Boolean = false) = open(
        MediaItem(entry.tmdbId, entry.type, entry.title, posterPath = entry.poster, backdropPath = entry.backdrop),
        autoplay,
    )
    /**
     * Straight to the player: the downloaded file if there is one, else the source used last time;
     * without either (or watched elsewhere), the sources list.
     */
    fun resume(entry: ProgressEntry) {
        val local = graph.downloads.local(entry.key)
        val saved = entry.lastStream
        when {
            local != null -> play(local.playback(PlaybackRequest.resume(entry, local.stream).startPositionMs))
            saved != null && saved.playable(graph.torrents.available) -> play(PlaybackRequest.resume(entry, saved))
            else -> details(entry, autoplay = true)
        }
    }

    // Notifications and launcher shortcuts land here (see MainActivity.handleIntent).
    LaunchedEffect(Unit) {
        links.collect { link ->
            link ?: return@collect
            links.value = null
            when (link) {
                is AppLink.Open -> nav.navigate(DetailRoute(link.type.tmdb, link.id, season = link.season))
                is AppLink.Resume -> graph.progress.get(link.progressKey)?.let(::resume)
                AppLink.Downloads -> nav.navigate(DownloadsRoute) { launchSingleTop = true }
            }
        }
    }

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
                    onConnectStremio = { switchTab(nav, SettingsRoute) },
                )
            }
            composable<ContinueRoute> {
                ContinueScreen(
                    bottomPadding,
                    onResume = ::resume,
                    onDetails = { details(it) },
                    onShow = { ep ->
                        graph.previews["${MediaType.Tv.tmdb}:${ep.tmdbId}"] = MediaItem(ep.tmdbId, MediaType.Tv, ep.show, posterPath = ep.poster, backdropPath = ep.backdrop)
                        nav.navigate(DetailRoute(MediaType.Tv.tmdb, ep.tmdbId, season = ep.season))
                    },
                )
            }
            composable<SearchRoute> {
                val vm: SearchViewModel = viewModel { SearchViewModel(graph) }
                SearchScreen(vm, bottomPadding, onOpen = { open(it) })
            }
            composable<LibraryRoute> {
                LibraryScreen(bottomPadding, onOpen = { open(it) }, onDownloads = { nav.navigate(DownloadsRoute) }, onStats = { nav.navigate(StatsRoute) })
            }
            composable<DownloadsRoute> {
                DownloadsScreen(
                    bottomPadding = 16.dp,
                    onBack = { nav.popBackStack() },
                    onPlay = { item ->
                        val resumeAt = graph.progress.get(item.id)?.takeIf { !it.finished && it.positionMs > 30_000 }?.positionMs ?: 0L
                        play(item.playback(resumeAt))
                    },
                )
            }
            composable<StatsRoute> {
                StatsScreen(onBack = { nav.popBackStack() }, onOpen = { open(it) })
            }
            composable<SettingsRoute> {
                SettingsScreen(bottomPadding)
            }
            composable<DetailRoute> { backStack ->
                val route = backStack.toRoute<DetailRoute>()
                val type = MediaType.fromTmdb(route.type) ?: MediaType.Movie
                val vm: DetailViewModel = viewModel { DetailViewModel(graph, type, route.id, route.autoplay, route.season) }
                DetailScreen(
                    vm, onBack = { nav.popBackStack() }, onOpen = { open(it) }, onPlay = ::play,
                    onPerson = { person, imdb -> nav.navigate(PersonRoute(person.name, person.personId, person.profilePath, imdb)) },
                )
            }
            composable<PersonRoute> { backStack ->
                val route = backStack.toRoute<PersonRoute>()
                val vm: PersonViewModel = viewModel { PersonViewModel(graph, route.name, route.personId, route.photo, route.titleImdb) }
                PersonScreen(vm, onBack = { nav.popBackStack() }, onOpen = { open(it) })
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
            .padding(horizontal = 12.dp, vertical = 12.dp)
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
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = destination?.hasRoute(tab.route::class) == true
            val label = stringResource(tab.label)
            Column(
                Modifier
                    .weight(1f)
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
                    Icon(tab.icon, label, tint = if (selected) HarborColors.Accent else HarborColors.TextDim, modifier = Modifier.size(22.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) HarborColors.Text else HarborColors.TextFaint,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
