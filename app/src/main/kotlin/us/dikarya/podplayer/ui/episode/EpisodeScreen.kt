/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.episode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.EpisodeItem
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.browse.episodeMeta
import us.dikarya.podplayer.ui.browse.rememberDownloadStarter
import us.dikarya.podplayer.ui.notes.LinkConfirmDialog
import us.dikarya.podplayer.ui.notes.toAnnotatedString
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** An episode's page (SPEC §12): sanitized notes with timestamp links; play, queue, download, mark played. */
@Composable
fun EpisodeScreen(viewModel: EpisodeViewModel, onOpenShow: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) return Loading()
    val item = state.item ?: return CenteredMessage(stringResource(R.string.episode_gone))
    var link by remember { mutableStateOf<RichStyle.Link?>(null) }
    val colors = PodplayerTheme.colors

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OfflineBanner(state.online)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.imageUrl, item.approvedHost, 96.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.episode.title ?: stringResource(R.string.untitled_episode), style = MaterialTheme.typography.titleLarge)
                    item.showTitle?.let { show ->
                        Text(
                            show,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) { onOpenShow(item.episode.feedId) }.padding(vertical = 4.dp),
                        )
                    }
                    Text(episodeMeta(item, withShow = false), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
            item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Actions(item, viewModel, state.downloadsWifiOnly)
            if (!state.online && !item.downloaded) {
                Text(stringResource(R.string.episode_offline_stream), style = MaterialTheme.typography.bodySmall, color = colors.warning)
            }
            HorizontalDivider()
            when (val notes = state.notes) {
                NotesState.Loading -> CircularProgressIndicator()
                is NotesState.Rejected -> Text(stringResource(R.string.notes_rejected), color = colors.textSecondary)
                is NotesState.Ready -> if (notes.text.text.isBlank()) {
                    Text(stringResource(R.string.notes_none), color = colors.textSecondary)
                } else {
                    val text = remember(notes.text, colors) {
                        notes.text.toAnnotatedString(colors, onLink = { link = it }, onTimestamp = viewModel::playAt)
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                }
            }
        }
    }
    link?.let { LinkConfirmDialog(it) { link = null } }
}

@Composable
private fun Actions(item: EpisodeItem, viewModel: EpisodeViewModel, downloadsWifiOnly: Boolean) {
    val actions = viewModel.actions
    val download = rememberDownloadStarter(actions)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.togglePlay(item) }) {
            Icon(painterResource(if (item.playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (item.playing) R.string.action_pause else if (item.positionMs > 0 && !item.played) R.string.action_resume else R.string.action_play))
        }
        OutlinedButton(onClick = { actions.toggleQueue(item) }) {
            Text(stringResource(if (item.queued) R.string.action_remove_from_queue else R.string.action_add_to_queue))
        }
        when (item.downloadState) {
            DownloadState.Complete -> OutlinedButton(onClick = { actions.deleteDownload(item.id) }) { Text(stringResource(R.string.action_delete_download)) }
            DownloadState.Queued, DownloadState.Downloading -> OutlinedButton(onClick = { actions.cancelDownload(item.id) }) {
                Text(stringResource(R.string.action_cancel_download_short))
            }
            DownloadState.Failed -> OutlinedButton(onClick = { download(item.id) }) { Text(stringResource(R.string.action_retry_download)) }
            DownloadState.Paused -> OutlinedButton(onClick = { download(item.id) }) { Text(stringResource(R.string.action_resume_download)) }
            null -> OutlinedButton(onClick = { download(item.id) }) { Text(stringResource(R.string.action_download)) }
        }
        OutlinedButton(onClick = { actions.setPlayed(item.id, !item.played) }) {
            Text(stringResource(if (item.played) R.string.action_mark_unplayed else R.string.action_mark_played))
        }
    }
    // A queued download hasn't started: say why, rather than showing only "Stop download".
    if (item.downloadState == DownloadState.Queued) {
        Text(
            stringResource(if (downloadsWifiOnly) R.string.download_waiting_for_wifi else R.string.download_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = PodplayerTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
