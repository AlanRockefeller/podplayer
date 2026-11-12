/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import us.dikarya.podplayer.BuildTypeSetup
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.ui.DataStateDialogs
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.discover.DiscoverScreen
import us.dikarya.podplayer.ui.discover.DiscoverViewModel
import us.dikarya.podplayer.ui.episode.EpisodeScreen
import us.dikarya.podplayer.ui.episode.EpisodeViewModel
import us.dikarya.podplayer.ui.home.HomeScreen
import us.dikarya.podplayer.ui.home.HomeViewModel
import us.dikarya.podplayer.ui.library.LibraryScreen
import us.dikarya.podplayer.ui.library.LibraryViewModel
import us.dikarya.podplayer.ui.player.MiniPlayerBar
import us.dikarya.podplayer.ui.player.MiniPlayerViewModel
import us.dikarya.podplayer.ui.settings.AppearanceScreen
import us.dikarya.podplayer.ui.settings.BackupSection
import us.dikarya.podplayer.ui.show.ShowScreen
import us.dikarya.podplayer.ui.show.ShowViewModel

@Serializable
data object HomeRoute

@Serializable
data object DiscoverRoute

@Serializable
data object LibraryRoute

@Serializable
data object QueueRoute

@Serializable
data object SettingsRoute

/** A show's page. */
@Serializable
data class ShowRoute(val feedId: Long)

/** An episode's page. */
@Serializable
data class EpisodeRoute(val episodeId: Long)

/** Only registered when the build has a [DebugScreen] (debug builds). */
@Serializable
data object DebugRoute

private enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    HOME(HomeRoute, HomeRoute::class, R.string.nav_home, R.drawable.ic_home),
    DISCOVER(DiscoverRoute, DiscoverRoute::class, R.string.nav_discover, R.drawable.ic_explore),
    LIBRARY(LibraryRoute, LibraryRoute::class, R.string.nav_library, R.drawable.ic_library_music),
    QUEUE(QueueRoute, QueueRoute::class, R.string.nav_queue, R.drawable.ic_queue_music),
}

private fun NavDestination?.isOn(routeClass: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(routeClass) } == true

/**
 * Bottom navigation (Home, Discover, Library, Queue), a Settings icon, and the mini-player above the navigation
 * while something is loaded (SPEC §12). Show and episode pages keep the navigation; Settings and the Developer
 * screen replace it with a back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, browse: BrowseDeps) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val debugScreen = BuildTypeSetup.debugScreen
    val onSettings = destination.isOn(SettingsRoute::class)
    val onDebug = debugScreen != null && destination.isOn(DebugRoute::class)
    val onShow = destination.isOn(ShowRoute::class)
    val onEpisode = destination.isOn(EpisodeRoute::class)
    val onSubScreen = onSettings || onDebug
    val canGoBack = onSubScreen || onShow || onEpisode
    val current = TopLevelDestination.entries.firstOrNull { destination.isOn(it.routeClass) }
    val miniPlayer = viewModel { MiniPlayerViewModel(browse) }
    DataStateDialogs()

    val openShow: (Long) -> Unit = { navController.navigate(ShowRoute(it)) { launchSingleTop = true } }
    val openEpisode: (Long) -> Unit = { navController.navigate(EpisodeRoute(it)) { launchSingleTop = true } }
    val openDiscover: () -> Unit = { navController.navigateToTab(TopLevelDestination.DISCOVER) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        onSettings -> R.string.settings_title
                        onDebug && debugScreen != null -> debugScreen.title
                        onShow -> R.string.show_title
                        onEpisode -> R.string.episode_title
                        current != null -> current.label
                        else -> R.string.app_name
                    }
                    Text(stringResource(title))
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    }
                },
                actions = {
                    if (!onSubScreen && debugScreen != null) {
                        TextButton(onClick = { navController.navigate(DebugRoute) { launchSingleTop = true } }) {
                            Text(stringResource(debugScreen.title))
                        }
                    }
                    if (!onSubScreen) {
                        IconButton(onClick = { navController.navigate(SettingsRoute) { launchSingleTop = true } }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.open_settings),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (!onSubScreen) MiniPlayerBar(miniPlayer, onOpen = openEpisode)
                if (!onSubScreen) BottomNavigation(navController, current)
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<HomeRoute> {
                HomeScreen(viewModel { HomeViewModel(browse) }, onOpenEpisode = openEpisode, onOpenShow = openShow, onDiscover = openDiscover)
            }
            composable<DiscoverRoute> { DiscoverScreen(viewModel { DiscoverViewModel(browse) }, onOpenShow = openShow) }
            composable<LibraryRoute> { LibraryScreen(viewModel { LibraryViewModel(browse) }, onOpenShow = openShow, onDiscover = openDiscover) }
            composable<QueueRoute> { PlaceholderScreen(R.string.placeholder_queue) }
            composable<ShowRoute> { backStack ->
                val route = backStack.toRoute<ShowRoute>()
                ShowScreen(viewModel { ShowViewModel(browse, route.feedId) }, onOpenEpisode = openEpisode, onGone = { navController.popBackStack() })
            }
            composable<EpisodeRoute> { backStack ->
                val route = backStack.toRoute<EpisodeRoute>()
                EpisodeScreen(viewModel { EpisodeViewModel(browse, route.episodeId) }, onOpenShow = openShow)
            }
            composable<SettingsRoute> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceScreen(themeMode, onThemeModeChange)
                    BackupSection()
                }
            }
            if (debugScreen != null) {
                composable<DebugRoute> { debugScreen.content() }
            }
        }
    }
}

private fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomNavigation(navController: NavHostController, current: TopLevelDestination?) {
    NavigationBar {
        TopLevelDestination.entries.forEach { item ->
            NavigationBarItem(
                selected = item == current,
                onClick = { navController.navigateToTab(item) },
                // The label below is the accessible name, so the icon itself is decorative.
                icon = { Icon(painter = painterResource(item.icon), contentDescription = null) },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}
