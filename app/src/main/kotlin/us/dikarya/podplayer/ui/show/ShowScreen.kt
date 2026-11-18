/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package us.dikarya.podplayer.ui.show

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.ui.player.SPEED_CHOICES
import us.dikarya.podplayer.ui.browse.ArtworkBeside
import us.dikarya.podplayer.ui.browse.PullToRefresh
import us.dikarya.podplayer.ui.browse.MIN_TITLE_EMS
import us.dikarya.podplayer.ui.browse.showNameOrUntitled
import us.dikarya.podplayer.ui.browse.textRoom
import us.dikarya.podplayer.ui.player.speedText
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.EpisodeRow
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.browse.StarUndoBar
import us.dikarya.podplayer.ui.browse.rememberDownloadStarter
import us.dikarya.podplayer.ui.notes.LinkConfirmDialog
import us.dikarya.podplayer.ui.notes.toAnnotatedString
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** A show's page (SPEC §12): header, per-show speed, episodes with sort; refresh and unsubscribe. */
@Composable
fun ShowScreen(viewModel: ShowViewModel, onOpenEpisode: (Long) -> Unit, onGone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download = rememberDownloadStarter(viewModel.actions)
    StarUndoBar(viewModel.actions)
    LaunchedEffect(state.gone) { if (state.gone) onGone() }
    if (state.loading) return Loading()
    val feed = state.feed ?: return
    var link by remember { mutableStateOf<RichStyle.Link?>(null) }
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }
    val colors = PodplayerTheme.colors

    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(false)
    // Pull down to check this show for new episodes (owner, 2026-11-18).
    PullToRefresh(refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { OfflineBanner(state.online) }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // At large font sizes the artwork moves above the title rather than squeeze it (see ArtworkBeside).
                val titleStyle = MaterialTheme.typography.titleLarge
                ArtworkBeside(
                    minTextWidth = textRoom(titleStyle.fontSize, MIN_TITLE_EMS),
                    artwork = { Box(Modifier.padding(end = 16.dp, bottom = 8.dp)) { Artwork(feed.imageUrl, feed.ownerApprovedHost, 96.dp) } },
                ) {
                    Column {
                        Text(showNameOrUntitled(feed), style = titleStyle)
                        feed.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
                        if (!feed.isSecure) Text(stringResource(R.string.not_secure_feed), style = MaterialTheme.typography.bodySmall, color = colors.warning)
                    }
                }
                state.description?.let { description ->
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    val text = remember(description, colors) { description.toAnnotatedString(colors, onLink = { link = it }, onTimestamp = null) }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
                    }
                }
                // A flow row: on a narrow phone, Unsubscribe moves to the next line instead of being squeezed to one
                // letter wide (seen on the owner's 360 dp phone).
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    SpeedPicker(state.showSpeed, state.globalSpeed, viewModel::setShowSpeed)
                    IconButton(onClick = viewModel::refresh) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh_show))
                    }
                    IconButton(onClick = viewModel::toggleSort) {
                        Icon(
                            painterResource(R.drawable.ic_sort),
                            contentDescription = stringResource(if (state.newestFirst) R.string.show_sort_oldest else R.string.show_sort_newest),
                        )
                    }
                    FilterChip(
                        selected = state.starredOnly,
                        onClick = viewModel::toggleStarredOnly,
                        label = { Text(stringResource(R.string.show_starred_only), maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    TextButton(onClick = { confirmUnsubscribe = true }) {
                        Text(stringResource(R.string.action_unsubscribe), maxLines = 1, softWrap = false)
                    }
                }
                AutoDownloadRow(state.autoDownload, viewModel::setAutoDownload)
            }
            HorizontalDivider()
        }
        if (state.episodes.isEmpty()) {
            val message = when {
                state.starredOnly -> R.string.show_no_starred
                feed.lastRefreshSuccessAt == null -> R.string.show_not_refreshed
                else -> R.string.show_no_episodes
            }
            item { CenteredMessage(stringResource(message)) }
        }
        items(state.episodes, key = { it.id }) { item ->
            EpisodeRow(item, withShow = false, onOpen = { onOpenEpisode(item.id) }, actions = viewModel.actions, onDownload = download)
        }
    }
    }

    link?.let { LinkConfirmDialog(it) { link = null } }
    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text(stringResource(R.string.unsubscribe_title, showNameOrUntitled(feed))) },
            text = { Text(stringResource(R.string.unsubscribe_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    viewModel.unsubscribe()
                }) { Text(stringResource(R.string.action_unsubscribe)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** SPEC §9 "Download new episodes": the whole row is the switch, at least 56 dp tall. */
@Composable
private fun AutoDownloadRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(TAG_SHOW_AUTO_DOWNLOAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.show_auto_download), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.show_auto_download_body),
                style = MaterialTheme.typography.bodySmall,
                color = PodplayerTheme.colors.textSecondary,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

const val TAG_SHOW_AUTO_DOWNLOAD = "show-auto-download"

/** SPEC §10: a show's own speed, or the global one. */
@Composable
private fun SpeedPicker(showSpeed: Float?, globalSpeed: Float, onChange: (Float?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(
                if (showSpeed == null) {
                    stringResource(R.string.speed_default_label, speedText(globalSpeed))
                } else {
                    stringResource(R.string.speed_show_label, speedText(showSpeed))
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.speed_use_default, speedText(globalSpeed))) },
                leadingIcon = { RadioButton(selected = showSpeed == null, onClick = null) },
                onClick = {
                    open = false
                    onChange(null)
                },
            )
            SPEED_CHOICES.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(speedText(speed)) },
                    leadingIcon = { RadioButton(selected = showSpeed == speed, onClick = null) },
                    onClick = {
                        open = false
                        onChange(speed)
                    },
                )
            }
        }
    }
}

private const val DESCRIPTION_LINES = 3
