/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.playback.SleepUi
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.DownloadButton
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.QueueButton
import us.dikarya.podplayer.ui.browse.rememberDownloadStarter
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/**
 * The full player (SPEC §12): artwork, title, show, seek bar with elapsed and remaining, back / play / forward,
 * speed, sleep timer, queue and download. It only shows the session's state and sends it commands.
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onOpenEpisode: (Long) -> Unit, onOpenQueue: () -> Unit) {
    LaunchedEffect(viewModel) { viewModel.connect() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    when {
        state.loading -> Loading()
        state.episodeId == null -> CenteredMessage(stringResource(R.string.player_nothing))
        else -> Player(state, viewModel, onOpenEpisode, onOpenQueue, onSheet = { sheet = it })
    }
    when (sheet) {
        Sheet.Speed -> SpeedSheet(state, viewModel, onDismiss = { sheet = Sheet.None })
        Sheet.Sleep -> SleepSheet(state.sleep, viewModel, onDismiss = { sheet = Sheet.None })
        Sheet.None -> Unit
    }
}

private enum class Sheet { None, Speed, Sleep }

@Composable
private fun Player(state: PlayerUiState, viewModel: PlayerViewModel, onOpenEpisode: (Long) -> Unit, onOpenQueue: () -> Unit, onSheet: (Sheet) -> Unit) {
    val colors = PodplayerTheme.colors
    val item = state.item
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(item?.imageUrl, item?.approvedHost, 280.dp, contentDescription = stringResource(R.string.artwork))
        Spacer(Modifier.height(20.dp))
        Text(
            state.title ?: stringResource(R.string.untitled_episode),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        state.showTitle?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        SeekBar(state, viewModel::seekTo)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::skipBack, modifier = Modifier.size(64.dp)) {
                Icon(
                    painterResource(R.drawable.ic_replay),
                    contentDescription = pluralStringResource(R.plurals.player_skip_back, state.skipBackSeconds, state.skipBackSeconds),
                    modifier = Modifier.size(36.dp),
                )
            }
            FilledIconButton(onClick = viewModel::togglePlay, modifier = Modifier.size(80.dp)) {
                Icon(
                    painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (state.playing) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(44.dp),
                )
            }
            IconButton(onClick = viewModel::skipForward, modifier = Modifier.size(64.dp)) {
                Icon(
                    painterResource(R.drawable.ic_forward_media),
                    contentDescription = pluralStringResource(R.plurals.player_skip_forward, state.skipForwardSeconds, state.skipForwardSeconds),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val speedDescription = stringResource(R.string.player_speed, speedText(state.speed))
            TextButton(
                onClick = { onSheet(Sheet.Speed) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = speedDescription },
            ) { Text(speedText(state.speed)) }
            val sleepDescription = when (val sleep = state.sleep) {
                SleepUi.Off -> stringResource(R.string.player_sleep)
                is SleepUi.Minutes -> stringResource(R.string.player_sleep_minutes_left, clockText(sleep.remainingMs))
                SleepUi.EndOfEpisode -> stringResource(R.string.player_sleep_end)
            }
            IconButton(onClick = { onSheet(Sheet.Sleep) }) {
                Icon(
                    painterResource(R.drawable.ic_bedtime),
                    contentDescription = sleepDescription,
                    tint = if (state.sleep == SleepUi.Off) colors.textSecondary else colors.accent,
                )
            }
            if (item != null) {
                QueueButton(item, viewModel.actions)
                DownloadButton(item, viewModel.actions, rememberDownloadStarter(viewModel.actions))
            }
            IconButton(onClick = onOpenQueue) {
                Icon(painterResource(R.drawable.ic_queue_music), contentDescription = stringResource(R.string.player_open_queue))
            }
        }
        (state.sleep as? SleepUi.Minutes)?.let {
            Text(stringResource(R.string.sleep_running, clockText(it.remainingMs)), style = MaterialTheme.typography.bodySmall, color = colors.accent)
        }
        if (state.sleep == SleepUi.EndOfEpisode) {
            Text(stringResource(R.string.sleep_running_end), style = MaterialTheme.typography.bodySmall, color = colors.accent)
        }
        Spacer(Modifier.height(8.dp))
        state.episodeId?.let { id ->
            OutlinedButton(onClick = { onOpenEpisode(id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.player_open_episode))
            }
        }
    }
}

/**
 * The seek bar follows the session; while the owner drags, it shows the finger's position, and the seek is sent
 * once, on release. That drag value is gesture state, not playback state.
 */
@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val colors = PodplayerTheme.colors
    val duration = state.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val description = stringResource(R.string.player_seek)
    if (duration != null && duration > 0) {
        val shown = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        )
        val position = dragging?.let { (it * duration).toLong() } ?: state.positionMs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val elapsedText = clockText(position)
            val remainingText = clockText(duration - position)
            val elapsedDescription = stringResource(R.string.player_elapsed, elapsedText)
            val remainingDescription = stringResource(R.string.player_remaining, remainingText)
            Text(
                elapsedText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.semantics { contentDescription = elapsedDescription },
            )
            Text(
                stringResource(R.string.player_remaining_short, remainingText),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.semantics { contentDescription = remainingDescription },
            )
        }
    } else {
        Text(clockText(state.positionMs), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(state: PlayerUiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var forShow by rememberSaveable { mutableStateOf(state.showSpeed != null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle(R.string.speed_sheet_title)
            Text(
                stringResource(R.string.speed_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = PodplayerTheme.colors.textQuiet,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Column(Modifier.selectableGroup()) {
                ChoiceRow(stringResource(R.string.speed_for_all), selected = !forShow) { forShow = false }
                ChoiceRow(stringResource(R.string.speed_for_show), selected = forShow) { forShow = true }
            }
            state.showSpeed?.let {
                Text(
                    stringResource(R.string.speed_show_uses_own, speedText(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = {
                        viewModel.useDefaultSpeedForShow()
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.speed_use_default_for_show)) }
            }
            Column(Modifier.selectableGroup()) {
                SPEED_CHOICES.forEach { speed ->
                    ChoiceRow(speedText(speed), selected = speed == state.speed) {
                        viewModel.setSpeed(speed, forShow)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(sleep: SleepUi, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle(R.string.sleep_sheet_title)
            val status = when (sleep) {
                SleepUi.Off -> stringResource(R.string.sleep_sheet_body)
                is SleepUi.Minutes -> stringResource(R.string.sleep_running, clockText(sleep.remainingMs))
                SleepUi.EndOfEpisode -> stringResource(R.string.sleep_running_end)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Column(Modifier.selectableGroup()) {
                SLEEP_MINUTES.forEach { minutes ->
                    ChoiceRow(pluralStringResource(R.plurals.sleep_minutes, minutes, minutes), selected = false) {
                        viewModel.sleepAfter(minutes)
                        onDismiss()
                    }
                }
                ChoiceRow(stringResource(R.string.sleep_end_of_episode), selected = sleep == SleepUi.EndOfEpisode) {
                    viewModel.sleepAtEndOfEpisode()
                    onDismiss()
                }
            }
            if (sleep != SleepUi.Off) {
                TextButton(
                    onClick = {
                        viewModel.cancelSleep()
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.sleep_off)) }
            }
        }
    }
}

@Composable
private fun SheetTitle(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
    )
}

/** One choice in a sheet: the whole row is the control (48 dp and more). */
@Composable
internal fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
