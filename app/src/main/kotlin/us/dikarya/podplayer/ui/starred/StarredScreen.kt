/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.starred

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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

/** Library › Starred (SPEC §12). */
@Composable
fun StarredScreen(viewModel: StarredViewModel, onOpenEpisode: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val download = rememberDownloadStarter(viewModel.actions)
    val context = LocalContext.current
    val resources = LocalResources.current
    StarUndoBar(viewModel.actions)
    when {
        state.loading -> return Loading()
        state.libraryClosed -> return LibraryClosed()
    }
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(state.online)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.starred_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).testTag(TAG_STARRED_SEARCH),
            )
            IconButton(
                onClick = {
                    val text = viewModel.shareText(resources.getString(R.string.starred_share_heading), resources.getString(R.string.untitled_episode))
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    try {
                        context.startActivity(Intent.createChooser(send, resources.getString(R.string.starred_share)))
                    } catch (_: ActivityNotFoundException) {
                        // Nothing on the phone takes text; there is nothing more to do.
                    }
                },
                enabled = state.items.isNotEmpty(),
            ) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.starred_share))
            }
        }
        when {
            state.nothingStarred -> CenteredMessage(stringResource(R.string.starred_empty))
            state.noMatches -> CenteredMessage(stringResource(R.string.starred_no_matches))
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.items, key = { it.id }) { item ->
                    EpisodeRow(item, withShow = true, onOpen = { onOpenEpisode(item.id) }, actions = viewModel.actions, onDownload = download)
                }
            }
        }
    }
}

const val TAG_STARRED_SEARCH = "starred-search"
