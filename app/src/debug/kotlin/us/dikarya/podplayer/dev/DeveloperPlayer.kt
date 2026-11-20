/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package us.dikarya.podplayer.dev

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.model.PositionChange
import us.dikarya.podplayer.playback.PlaybackClient
import us.dikarya.podplayer.playback.SleepUi
import us.dikarya.podplayer.ui.player.SeekDrag

/**
 * Play, pause, seek, skip, speed and sleep timer on the Developer screen (SPEC §12), so playback can be tested
 * on a phone before the real player screen exists (S9). It only observes the session and sends commands.
 */
@Composable
internal fun DeveloperPlayer(client: PlaybackClient) {
    LaunchedEffect(client) { client.connect() }
    val state by client.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.episodeId == null) {
            Text(stringResource(if (state.connected) R.string.dev_nothing_loaded else R.string.dev_connecting))
            return@Column
        }
        Text(state.title ?: stringResource(R.string.dev_untitled), style = MaterialTheme.typography.bodyLarge)
        state.showTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val duration = state.durationMs
        Text(
            stringResource(
                R.string.dev_player_status,
                elapsed(state.positionMs),
                duration?.let(::elapsed) ?: "?",
                state.speed,
                stringResource(
                    when {
                        state.buffering -> R.string.dev_buffering
                        state.isPlaying -> R.string.dev_playing
                        else -> R.string.dev_paused
                    },
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (duration != null) {
            // While dragging, the slider shows the drag; the seek is sent once, on release, and only to the
            // episode the drag started on.
            val drag = remember { SeekDrag() }
            val episodeId = state.episodeId
            LaunchedEffect(episodeId) { drag.onEpisode(episodeId) }
            Slider(
                value = drag.shown(episodeId) ?: state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { drag.move(it, episodeId) },
                onValueChangeFinished = { drag.release(episodeId)?.let { client.seekTo(it.toLong()) } },
                valueRange = 0f..duration.toFloat(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { client.skipBack() }) { Text(stringResource(R.string.dev_skip_back)) }
            Button(onClick = { if (state.playWhenReady) client.pause() else client.play() }) {
                Text(stringResource(if (state.playWhenReady) R.string.dev_pause else R.string.dev_play))
            }
            OutlinedButton(onClick = { client.skipForward() }) { Text(stringResource(R.string.dev_skip_forward)) }
        }

        var forShow by rememberSaveable { mutableStateOf(false) }
        Text(stringResource(R.string.dev_speed), style = MaterialTheme.typography.labelMedium)
        FlowRow {
            for (speed in listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)) {
                TextButton(onClick = { client.setSpeed(speed, forShow) }) { Text(stringResource(R.string.dev_speed_value, speed)) }
            }
            if (forShow) TextButton(onClick = { client.setSpeed(null, forShow = true) }) { Text(stringResource(R.string.dev_speed_clear_show)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = forShow, onCheckedChange = { forShow = it })
            Text(stringResource(R.string.dev_speed_for_show))
        }

        Text(
            when (val sleep = state.sleep) {
                SleepUi.Off -> stringResource(R.string.dev_sleep_off)
                SleepUi.EndOfEpisode -> stringResource(R.string.dev_sleep_end_of_episode_on)
                is SleepUi.Minutes -> stringResource(R.string.dev_sleep_minutes_on, elapsed(sleep.remainingMs.coerceAtLeast(0)))
            },
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow {
            for (minutes in listOf(5, 15, 30, 60)) {
                TextButton(onClick = { client.sleepAfterMinutes(minutes) }) { Text(stringResource(R.string.dev_sleep_minutes, minutes)) }
            }
            TextButton(onClick = { client.sleepAtEndOfEpisode() }) { Text(stringResource(R.string.dev_sleep_end_of_episode)) }
            TextButton(onClick = { client.cancelSleepTimer() }) { Text(stringResource(R.string.dev_sleep_cancel)) }
        }
    }
}

@Composable
internal fun PositionLogRow(row: PositionChange) {
    val context = LocalContext.current
    Column {
        Text(
            "${DateUtils.formatDateTime(context, row.at, DateUtils.FORMAT_SHOW_TIME)} · ${row.cause.name} · #${row.episodeId}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            stringResource(
                R.string.dev_position_row,
                elapsed(row.fromMs),
                elapsed(row.toMs),
                stringResource(if (row.streaming) R.string.dev_stream else R.string.dev_file),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun elapsed(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)
