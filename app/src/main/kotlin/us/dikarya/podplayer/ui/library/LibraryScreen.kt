/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateUtils
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.ActionsBeside
import us.dikarya.podplayer.ui.browse.PullToRefresh
import us.dikarya.podplayer.ui.browse.showNameOrUntitled
import us.dikarya.podplayer.core.data.settings.LibraryLayout
import us.dikarya.podplayer.core.data.settings.LibrarySort
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.FindShowsButton
import us.dikarya.podplayer.ui.browse.LibraryClosed
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Library (SPEC §12): every subscription, as a grid or a list, sorted by the owner's choice. */
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onOpenShow: (Long) -> Unit, onDiscover: () -> Unit, onOpenStarred: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.loading -> return Loading()
        state.libraryClosed -> return LibraryClosed()
    }
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(state.online)
        Toolbar(state, viewModel, onOpenStarred)
        if (state.shows.isEmpty()) {
            CenteredMessage(stringResource(R.string.library_empty)) { FindShowsButton(onDiscover) }
            return@Column
        }
        val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(false)
        // Pull down to check every show for new episodes (owner, 2026-11-18).
        PullToRefresh(refreshing, onRefresh = viewModel::refreshAll, modifier = Modifier.fillMaxSize()) {
            when (state.layout) {
                LibraryLayout.Grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.shows, key = { it.feed.id }) { show -> GridTile(show, onOpenShow) }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier) }
                }
                LibraryLayout.List -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(state.shows, key = { it.feed.id }) { show -> ListRow(show, onOpenShow) }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(state: LibraryUiState, viewModel: LibraryViewModel, onOpenStarred: () -> Unit) {
    // At large font sizes the buttons move under the count rather than break it ("227 sho / ws" on the phone).
    ActionsBeside(
        minMainWidth = null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        main = {
            Text(
                pluralStringResource(R.plurals.library_count, state.shows.size, state.shows.size),
                style = MaterialTheme.typography.labelLarge,
                color = PodplayerTheme.colors.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        },
        actions = { ToolbarActions(state, viewModel, onOpenStarred) },
    )
}

@Composable
private fun ToolbarActions(state: LibraryUiState, viewModel: LibraryViewModel, onOpenStarred: () -> Unit) {
    var sorting by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpenStarred) {
            Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = PodplayerTheme.colors.accent)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.library_starred), maxLines = 1, softWrap = false)
        }
        IconButton(onClick = viewModel::refreshAll) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh_all))
        }
        Box {
            IconButton(onClick = { sorting = true }) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.library_sort))
            }
            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                LibrarySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabel(sort))) },
                        leadingIcon = { RadioButton(selected = sort == state.sort, onClick = null) },
                        onClick = {
                            sorting = false
                            viewModel.setSort(sort)
                        },
                    )
                }
            }
        }
        val grid = state.layout == LibraryLayout.Grid
        IconButton(onClick = { viewModel.setLayout(if (grid) LibraryLayout.List else LibraryLayout.Grid) }) {
            Icon(
                painterResource(if (grid) R.drawable.ic_list else R.drawable.ic_grid),
                contentDescription = stringResource(if (grid) R.string.library_show_list else R.string.library_show_grid),
            )
        }
    }
}

private fun sortLabel(sort: LibrarySort): Int = when (sort) {
    LibrarySort.Title -> R.string.library_sort_title
    LibrarySort.NewestEpisode -> R.string.library_sort_newest
    LibrarySort.Subscribed -> R.string.library_sort_subscribed
}

@Composable
private fun GridTile(show: ShowItem, onOpenShow: (Long) -> Unit) {
    Column(Modifier.clickable(role = Role.Button) { onOpenShow(show.feed.id) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(show.feed.imageUrl, show.feed.ownerApprovedHost, 160.dp, Modifier.fillMaxSize())
        }
        Text(
            showNameOrUntitled(show.feed),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ListRow(show: ShowItem, onOpenShow: (Long) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenShow(show.feed.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(show.feed.imageUrl, show.feed.ownerApprovedHost, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(showNameOrUntitled(show.feed), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            show.feed.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PodplayerTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            show.newestEpisodeAt?.let {
                Text(
                    stringResource(R.string.library_newest, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PodplayerTheme.colors.textQuiet,
                )
            }
        }
    }
}
