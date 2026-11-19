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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import us.dikarya.podplayer.ui.browse.DownloadFailureBar
import us.dikarya.podplayer.ui.discover.ShowPreviewScreen
import us.dikarya.podplayer.ui.discover.ShowPreviewViewModel
import us.dikarya.podplayer.ui.downloads.DownloadsScreen
import us.dikarya.podplayer.ui.downloads.DownloadsViewModel
import us.dikarya.podplayer.ui.browse.LocalSnackbars
import us.dikarya.podplayer.ui.discover.DiscoverScreen
import us.dikarya.podplayer.ui.discover.DiscoverViewModel
import us.dikarya.podplayer.ui.discover.importlist.ListImportScreen
import us.dikarya.podplayer.ui.discover.importlist.ListImportViewModel
import us.dikarya.podplayer.ui.starred.StarredScreen
import us.dikarya.podplayer.ui.starred.StarredViewModel
import us.dikarya.podplayer.ui.episode.EpisodeScreen
import us.dikarya.podplayer.ui.episode.EpisodeViewModel
import us.dikarya.podplayer.ui.home.HomeScreen
import us.dikarya.podplayer.ui.home.HomeViewModel
import us.dikarya.podplayer.ui.library.LibraryScreen
import us.dikarya.podplayer.ui.library.LibraryViewModel
import us.dikarya.podplayer.ui.player.MiniPlayerBar
import us.dikarya.podplayer.ui.player.MiniPlayerViewModel
import us.dikarya.podplayer.ui.player.PlayerScreen
import us.dikarya.podplayer.ui.player.PlayerViewModel
import us.dikarya.podplayer.ui.queue.QueueScreen
import us.dikarya.podplayer.ui.queue.QueueViewModel
import us.dikarya.podplayer.ui.settings.LicensesScreen
import us.dikarya.podplayer.ui.settings.LogsScreen
import us.dikarya.podplayer.ui.settings.LogsViewModel
import us.dikarya.podplayer.ui.settings.SettingsDeps
import us.dikarya.podplayer.ui.settings.SettingsScreen
import us.dikarya.podplayer.ui.settings.SettingsViewModel
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

/** The full player. */
@Serializable
data object PlayerRoute

/** Settings › Logs. */
@Serializable
data object LogsRoute

/** Settings › About › Open-source licenses. */
@Serializable
data object LicensesRoute

/** Library › Starred. */
@Serializable
data object StarredRoute

/**
 * Discover › a search result's show, before subscribing. [feedUrl] is the search result's (public, from Apple's
 * directory); nothing here approves a host.
 */
@Serializable
data class ShowPreviewRoute(val feedUrl: String, val artworkUrl: String?)

/** Library › Downloads. */
@Serializable
data object DownloadsRoute

/** Discover › Import a list of shows (also where shared text lands). */
@Serializable
data object ImportListRoute

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
fun AppShell(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, browse: BrowseDeps, settings: SettingsDeps) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val debugScreen = BuildTypeSetup.debugScreen
    val onSettings = destination.isOn(SettingsRoute::class)
    val onLogs = destination.isOn(LogsRoute::class)
    val onLicenses = destination.isOn(LicensesRoute::class)
    val onPlayer = destination.isOn(PlayerRoute::class)
    val onDebug = debugScreen != null && destination.isOn(DebugRoute::class)
    val onShow = destination.isOn(ShowRoute::class)
    val onEpisode = destination.isOn(EpisodeRoute::class)
    val onStarred = destination.isOn(StarredRoute::class)
    val onImportList = destination.isOn(ImportListRoute::class)
    val onDownloads = destination.isOn(DownloadsRoute::class)
    val onShowPreview = destination.isOn(ShowPreviewRoute::class)
    val onSubScreen = onSettings || onLogs || onLicenses || onPlayer || onDebug
    val canGoBack = onSubScreen || onShow || onEpisode || onStarred || onImportList || onDownloads || onShowPreview
    val snackbars = remember { SnackbarHostState() }
    val current = TopLevelDestination.entries.firstOrNull { destination.isOn(it.routeClass) }
    val miniPlayer = viewModel { MiniPlayerViewModel(browse) }
    DataStateDialogs()

    val openShow: (Long) -> Unit = { navController.navigate(ShowRoute(it)) { launchSingleTop = true } }
    val openEpisode: (Long) -> Unit = { navController.navigate(EpisodeRoute(it)) { launchSingleTop = true } }
    val openDiscover: () -> Unit = { navController.navigateToTab(TopLevelDestination.DISCOVER) }
    val openPlayer: () -> Unit = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
    val openQueue: () -> Unit = { navController.navigateToTab(TopLevelDestination.QUEUE) }
    val openStarred: () -> Unit = { navController.navigate(StarredRoute) { launchSingleTop = true } }
    val openImportList: () -> Unit = { navController.navigate(ImportListRoute) { launchSingleTop = true } }
    val openDownloads: () -> Unit = { navController.navigate(DownloadsRoute) { launchSingleTop = true } }

    // Text shared into the app goes to the list importer, which takes it from BrowseDeps.sharedText.
    val shared by browse.sharedText.collectAsState()
    LaunchedEffect(shared) {
        if (shared != null) {
            // On a cold start the effect can run before NavHost has set its graph (seen on the owner's phone: every
            // share opened Home with an empty importer). Wait for the first back-stack entry, then navigate.
            navController.currentBackStackEntryFlow.first()
            navController.navigateToTab(TopLevelDestination.DISCOVER)
            openImportList()
        }
    }

    CompositionLocalProvider(LocalSnackbars provides snackbars) {
    DownloadFailureBar(browse)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        onSettings -> R.string.settings_title
                        onLogs -> R.string.logs_title
                        onLicenses -> R.string.settings_licenses
                        onPlayer -> R.string.player_title
                        onDebug && debugScreen != null -> debugScreen.title
                        onShow -> R.string.show_title
                        onEpisode -> R.string.episode_title
                        onStarred -> R.string.starred_title
                        onImportList -> R.string.import_list_title
                        onDownloads -> R.string.downloads_title
                        onShowPreview -> R.string.preview_title
                        current != null -> current.label
                        else -> R.string.app_name
                    }
                    // One line, shrunk to fit (it broke as "Episo / de" at font size 200%).
                    OneLine(stringResource(title), TITLE_MIN)
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
                if (!onSubScreen) MiniPlayerBar(miniPlayer, onOpen = { openPlayer() })
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
                HomeScreen(
                    viewModel { HomeViewModel(browse) },
                    onOpenEpisode = openEpisode,
                    onOpenShow = openShow,
                    onDiscover = openDiscover,
                    onOpenStarred = openStarred,
                )
            }
            composable<DiscoverRoute> {
                DiscoverScreen(
                    viewModel { DiscoverViewModel(browse) },
                    onOpenShow = openShow,
                    onImportList = openImportList,
                    onPreview = { hit -> navController.navigate(ShowPreviewRoute(hit.feedUrl, hit.artworkUrl)) { launchSingleTop = true } },
                )
            }
            composable<ShowPreviewRoute> { backStack ->
                val route = backStack.toRoute<ShowPreviewRoute>()
                ShowPreviewScreen(
                    viewModel { ShowPreviewViewModel(browse, route.feedUrl) },
                    artworkUrl = route.artworkUrl,
                    // The show replaces its preview, so Back goes to the search results.
                    onOpenShow = { id -> navController.navigate(ShowRoute(id)) { popUpTo<ShowPreviewRoute> { inclusive = true } } },
                )
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    viewModel { LibraryViewModel(browse) },
                    onOpenShow = openShow,
                    onDiscover = openDiscover,
                    onOpenStarred = openStarred,
                    onOpenDownloads = openDownloads,
                )
            }
            composable<StarredRoute> { StarredScreen(viewModel { StarredViewModel(browse) }, onOpenEpisode = openEpisode) }
            composable<DownloadsRoute> { DownloadsScreen(viewModel { DownloadsViewModel(browse) }, onOpenEpisode = openEpisode) }
            composable<ImportListRoute> {
                val vm = viewModel { ListImportViewModel(browse) }
                // A second share while the importer is open replaces what it holds.
                LaunchedEffect(shared) { vm.takeSharedText() }
                ListImportScreen(vm, onOpenShow = openShow, onDone = { navController.popBackStack() })
            }
            composable<QueueRoute> { QueueScreen(viewModel { QueueViewModel(browse) }, onOpenEpisode = openEpisode) }
            composable<PlayerRoute> {
                PlayerScreen(viewModel { PlayerViewModel(browse) }, onOpenEpisode = openEpisode, onOpenQueue = openQueue)
            }
            composable<ShowRoute> { backStack ->
                val route = backStack.toRoute<ShowRoute>()
                ShowScreen(viewModel { ShowViewModel(browse, route.feedId) }, onOpenEpisode = openEpisode, onGone = { navController.popBackStack() })
            }
            composable<EpisodeRoute> { backStack ->
                val route = backStack.toRoute<EpisodeRoute>()
                EpisodeScreen(viewModel { EpisodeViewModel(browse, route.episodeId) }, onOpenShow = openShow)
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    viewModel { SettingsViewModel(settings) },
                    themeMode,
                    onThemeModeChange,
                    onOpenLogs = { navController.navigate(LogsRoute) { launchSingleTop = true } },
                    onOpenLicenses = { navController.navigate(LicensesRoute) { launchSingleTop = true } },
                    appVersion = settings.appVersion,
                )
            }
            composable<LogsRoute> { LogsScreen(viewModel { LogsViewModel(settings) }) }
            composable<LicensesRoute> { LicensesScreen() }
            if (debugScreen != null) {
                composable<DebugRoute> { debugScreen.content() }
            }
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
                // One line, shrunk to fit: at font size 200% "Discover" broke as "Discov / er" on the owner's phone.
                label = { OneLine(stringResource(item.label), BAR_LABEL_MIN) },
            )
        }
    }
}

/** [text] on one line at the surrounding style's size, shrunk down to [min] when it doesn't fit, then cut with "…". */
@Composable
private fun OneLine(text: String, min: TextUnit) {
    val size = LocalTextStyle.current.fontSize
    if (!size.isSpecified || size.value <= min.value) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = size, stepSize = 0.5.sp),
    )
}

private val BAR_LABEL_MIN = 8.sp
private val TITLE_MIN = 14.sp
