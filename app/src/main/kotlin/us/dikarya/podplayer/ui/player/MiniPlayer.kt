/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Above the navigation while the session has an episode loaded (SPEC §12); nothing otherwise. */
@Composable
fun MiniPlayerBar(viewModel: MiniPlayerViewModel, onOpen: (Long) -> Unit) {
    LaunchedEffect(viewModel) { viewModel.connect() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { MiniPlayer(it, onToggle = viewModel::togglePlay, onOpen = { onOpen(it.episodeId) }) }
}

@Composable
fun MiniPlayer(state: MiniPlayerUi, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PodplayerTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surfaceElevated).testTag(TAG_MINI_PLAYER)) {
        val progress = state.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else if (state.buffering) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.mini_player_open), onClick = onOpen)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(state.imageUrl, state.approvedHost, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title ?: stringResource(R.string.untitled_episode), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.showTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (state.playing) R.string.action_pause else R.string.action_play),
                    tint = colors.accent,
                )
            }
        }
    }
}

const val TAG_MINI_PLAYER = "mini-player"
