/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.ActionsBeside
import us.dikarya.podplayer.ui.browse.EpisodeTitleStyle
import us.dikarya.podplayer.ui.browse.ShowNameLine
import us.dikarya.podplayer.ui.browse.MIN_TITLE_EMS
import us.dikarya.podplayer.ui.browse.showNameOrUntitled
import us.dikarya.podplayer.ui.browse.textRoom
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.EpisodeItem
import us.dikarya.podplayer.ui.browse.EpisodeRow
import us.dikarya.podplayer.ui.browse.FindShowsButton
import us.dikarya.podplayer.ui.browse.LibraryClosed
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.browse.PullToRefresh
import us.dikarya.podplayer.ui.browse.SectionHeading
import us.dikarya.podplayer.ui.browse.StarUndoBar
import us.dikarya.podplayer.ui.browse.episodeMeta
import us.dikarya.podplayer.ui.browse.rememberDownloadStarter
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Home (SPEC §12): the Continue card, New episodes, Your shows. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenEpisode: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
    onDiscover: () -> Unit,
    onOpenStarred: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download = rememberDownloadStarter(viewModel.actions)
    StarUndoBar(viewModel.actions)
    when {
        state.loading -> return Loading()
        state.libraryClosed -> return LibraryClosed()
    }
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(false)
    // Pull down to check every show for new episodes (owner, 2026-11-18).
    PullToRefresh(refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { OfflineBanner(state.online) }
        if (state.noShows) {
            item { CenteredMessage(stringResource(R.string.home_empty)) { FindShowsButton(onDiscover) } }
            return@LazyColumn
        }
        state.continueItem?.let { item ->
            item(key = "continue") {
                SectionHeading(R.string.home_continue)
                ContinueCard(item, onOpen = { onOpenEpisode(item.id) }, onPlay = { viewModel.actions.togglePlay(item) })
            }
        }
        item(key = "shows") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(R.string.home_your_shows, Modifier.weight(1f))
                IconButton(onClick = viewModel::refresh) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh_all))
                }
            }
            ShowsRow(state.shows, onOpenShow)
        }
        if (state.starred.isNotEmpty()) {
            item(key = "starred") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionHeading(R.string.home_starred, Modifier.weight(1f))
                    TextButton(onClick = onOpenStarred) { Text(stringResource(R.string.home_starred_all)) }
                }
                StarredRow(state.starred, onOpenEpisode)
            }
        }
        item(key = "new-heading") { SectionHeading(R.string.home_new_episodes) }
        if (state.newEpisodes.isEmpty()) {
            item(key = "new-empty") { CenteredMessage(stringResource(R.string.home_no_new_episodes)) }
        }
        // Star and queue live on the episode page; Home rows keep the title room (owner, 2026-11-16).
        itemsIndexed(state.newEpisodes, key = { _, item -> "new-${item.id}" }) { index, item ->
            EpisodeRow(item, withShow = true, onOpen = { onOpenEpisode(item.id) }, actions = viewModel.actions, onDownload = download, compact = true)
            // Near the end of the list: ask for older episodes (keyed on the size, so once per page).
            if (index >= state.newEpisodes.size - LOAD_MORE_AHEAD && state.canLoadMore) {
                LaunchedEffect(state.newEpisodes.size) { viewModel.loadMore() }
            }
        }
    }
    }
}

@Composable
private fun ContinueCard(item: EpisodeItem, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = PodplayerTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(role = Role.Button, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
    ) {
        val title = EpisodeTitleStyle
        // At large font sizes Resume moves under the title rather than squeeze it (see ActionsBeside).
        ActionsBeside(
            minMainWidth = textRoom(title.fontSize, MIN_TITLE_EMS, extra = CONTINUE_ARTWORK + 12.dp),
            modifier = Modifier.padding(12.dp),
            main = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(item.imageUrl, item.approvedHost, CONTINUE_ARTWORK)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ShowNameLine(item.showTitle)
                        Text(item.episode.title ?: stringResource(R.string.untitled_episode), style = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(episodeMeta(item, withShow = false), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            actions = {
                Button(onClick = onPlay, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                    Icon(painterResource(if (item.playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (item.playing) R.string.action_pause else if (item.positionMs > 0) R.string.action_resume else R.string.action_play))
                }
            },
        )
    }
}

@Composable
private fun StarredRow(items: List<EpisodeItem>, onOpenEpisode: (Long) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { item ->
            Column(Modifier.width(120.dp).clickable(role = Role.Button) { onOpenEpisode(item.id) }) {
                Artwork(item.imageUrl, item.approvedHost, 120.dp)
                Text(
                    item.episode.title ?: stringResource(R.string.untitled_episode),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                item.showTitle?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = PodplayerTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ShowsRow(shows: List<Feed>, onOpenShow: (Long) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(shows, key = { it.id }) { feed ->
            Column(Modifier.width(96.dp).clickable(role = Role.Button) { onOpenShow(feed.id) }) {
                Artwork(feed.imageUrl, feed.ownerApprovedHost, 96.dp)
                Text(
                    showNameOrUntitled(feed),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** How many rows before the end Home asks for older episodes. */
private const val LOAD_MORE_AHEAD = 5

private val CONTINUE_ARTWORK = 72.dp
