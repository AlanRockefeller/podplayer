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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.ui.DataStateDialogs
import us.dikarya.podplayer.ui.settings.AppearanceScreen
import us.dikarya.podplayer.ui.settings.BackupSection

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

private enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    @param:StringRes val placeholder: Int,
    @param:DrawableRes val icon: Int,
) {
    HOME(HomeRoute, HomeRoute::class, R.string.nav_home, R.string.placeholder_home, R.drawable.ic_home),
    DISCOVER(DiscoverRoute, DiscoverRoute::class, R.string.nav_discover, R.string.placeholder_discover, R.drawable.ic_explore),
    LIBRARY(LibraryRoute, LibraryRoute::class, R.string.nav_library, R.string.placeholder_library, R.drawable.ic_library_music),
    QUEUE(QueueRoute, QueueRoute::class, R.string.nav_queue, R.string.placeholder_queue, R.drawable.ic_queue_music),
}

private fun NavDestination?.isOn(routeClass: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(routeClass) } == true

/** Bottom navigation (Home, Discover, Library, Queue), a Settings icon, and the mini-player slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onSettings = destination.isOn(SettingsRoute::class)
    val current = TopLevelDestination.entries.firstOrNull { destination.isOn(it.routeClass) }
    DataStateDialogs()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        onSettings -> R.string.settings_title
                        current != null -> current.label
                        else -> R.string.app_name
                    }
                    Text(stringResource(title))
                },
                navigationIcon = {
                    if (onSettings) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    }
                },
                actions = {
                    if (!onSettings) {
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
                MiniPlayerSlot()
                if (!onSettings) BottomNavigation(navController, current)
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<HomeRoute> { PlaceholderScreen(TopLevelDestination.HOME.placeholder) }
            composable<DiscoverRoute> { PlaceholderScreen(TopLevelDestination.DISCOVER.placeholder) }
            composable<LibraryRoute> { PlaceholderScreen(TopLevelDestination.LIBRARY.placeholder) }
            composable<QueueRoute> { PlaceholderScreen(TopLevelDestination.QUEUE.placeholder) }
            composable<SettingsRoute> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceScreen(themeMode, onThemeModeChange)
                    BackupSection()
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(navController: NavHostController, current: TopLevelDestination?) {
    NavigationBar {
        TopLevelDestination.entries.forEach { item ->
            NavigationBarItem(
                selected = item == current,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                // The label below is the accessible name, so the icon itself is decorative.
                icon = { Icon(painter = painterResource(item.icon), contentDescription = null) },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}

/**
 * Reserved for the mini-player, which sits above the navigation bar when something is loaded
 * (SPEC §12). Empty until playback exists (M1-S8).
 */
@Composable
fun MiniPlayerSlot(content: (@Composable () -> Unit)? = null) {
    content?.invoke()
}
