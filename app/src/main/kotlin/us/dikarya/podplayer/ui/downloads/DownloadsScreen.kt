/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.EpisodeRow
import us.dikarya.podplayer.ui.browse.LibraryClosed
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.browse.StarUndoBar
import us.dikarya.podplayer.ui.browse.rememberDownloadStarter
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Library › Downloads (owner, 2026-11-18). */
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onOpenEpisode: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download = rememberDownloadStarter(viewModel.actions)
    val context = LocalContext.current
    StarUndoBar(viewModel.actions)
    when {
        state.loading -> return Loading()
        state.libraryClosed -> return LibraryClosed()
    }
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(state.online)
        if (state.empty) return@Column CenteredMessage(stringResource(R.string.downloads_empty))
        Text(
            pluralStringResource(
                R.plurals.downloads_summary,
                state.readyCount,
                state.readyCount,
                Formatter.formatShortFileSize(context, state.readyBytes),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = PodplayerTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag(TAG_DOWNLOADS_SUMMARY),
        )
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.items, key = { it.id }) { item ->
                EpisodeRow(item, withShow = true, onOpen = { onOpenEpisode(item.id) }, actions = viewModel.actions, onDownload = download)
            }
        }
    }
}

const val TAG_DOWNLOADS_SUMMARY = "downloads-summary"
