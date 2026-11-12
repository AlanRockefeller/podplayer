/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.ui.notes.openLink
import us.dikarya.podplayer.ui.player.ChoiceRow
import us.dikarya.podplayer.ui.player.SPEED_CHOICES
import us.dikarya.podplayer.ui.player.speedText
import us.dikarya.podplayer.ui.theme.PodplayerTheme

const val TAG_SETTINGS_LIST = "settings-list"
private const val MPL_URL = "https://www.mozilla.org/en-US/MPL/2.0/"

/**
 * Settings (SPEC §12): Appearance, Playback, Downloads and storage, Backup and restore, Logs, About. Changes are
 * saved as they are made. [backup] is the Backup and restore section (it needs the running app, so tests leave it out).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenLicenses: () -> Unit,
    backup: @Composable () -> Unit = { BackupSection() },
    appVersion: String = "",
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(TAG_SETTINGS_LIST)) {
        AppearanceScreen(themeMode, onThemeModeChange)
        HorizontalDivider(color = PodplayerTheme.colors.divider)
        PlaybackSection(state, viewModel)
        HorizontalDivider(color = PodplayerTheme.colors.divider)
        DownloadsSection(state, viewModel)
        HorizontalDivider(color = PodplayerTheme.colors.divider)
        backup()
        HorizontalDivider(color = PodplayerTheme.colors.divider)
        LinkRow(R.string.settings_logs, stringResource(R.string.settings_logs_body), onOpenLogs)
        HorizontalDivider(color = PodplayerTheme.colors.divider)
        AboutSection(appVersion, onOpenLicenses)
    }
}

@Composable
private fun PlaybackSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val playback = state.playback
    Heading(R.string.settings_playback)
    ChoiceSetting(
        title = R.string.settings_speed,
        body = stringResource(R.string.settings_speed_body),
        value = speedText(playback.speed),
        choices = SPEED_CHOICES,
        selected = playback.speed,
        label = { speedText(it) },
        onPick = viewModel::setSpeed,
    )
    val seconds: @Composable (Int) -> String = { stringResource(R.string.settings_seconds, it) }
    ChoiceSetting(
        title = R.string.settings_skip_back,
        value = seconds(playback.skipBackSeconds),
        choices = SettingsViewModel.SKIP_BACK_CHOICES,
        selected = playback.skipBackSeconds,
        label = seconds,
        onPick = viewModel::setSkipBack,
    )
    ChoiceSetting(
        title = R.string.settings_skip_forward,
        value = seconds(playback.skipForwardSeconds),
        choices = SettingsViewModel.SKIP_FORWARD_CHOICES,
        selected = playback.skipForwardSeconds,
        label = seconds,
        onPick = viewModel::setSkipForward,
    )
    val pause = minutesText(playback.rewindAfterPauseMinutes)
    SwitchSetting(
        title = R.string.settings_rewind_after_pause,
        body = stringResource(R.string.settings_rewind_body, playback.rewindSeconds, pause),
        checked = playback.rewindAfterPause,
        onChange = viewModel::setRewindAfterPause,
    )
    if (playback.rewindAfterPause) {
        ChoiceSetting(
            title = R.string.settings_rewind_by,
            value = seconds(playback.rewindSeconds),
            choices = SettingsViewModel.REWIND_SECONDS_CHOICES,
            selected = playback.rewindSeconds,
            label = seconds,
            onPick = viewModel::setRewindSeconds,
        )
        ChoiceSetting(
            title = R.string.settings_rewind_after,
            value = pause,
            choices = SettingsViewModel.REWIND_AFTER_MINUTES_CHOICES,
            selected = playback.rewindAfterPauseMinutes,
            label = { minutesText(it) },
            onPick = viewModel::setRewindAfterMinutes,
        )
    }
}

@Composable
private fun minutesText(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) stringResource(R.string.settings_hours, minutes / 60) else stringResource(R.string.settings_minutes, minutes)

@Composable
private fun DownloadsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val bytes: (Long) -> String = { Formatter.formatShortFileSize(context, it) }
    Heading(R.string.settings_downloads)
    SwitchSetting(
        title = R.string.settings_wifi_only,
        body = stringResource(R.string.settings_wifi_only_body),
        checked = state.wifiOnly,
        onChange = viewModel::setWifiOnly,
    )
    ChoiceSetting(
        title = R.string.settings_storage_cap,
        value = bytes(state.capBytes),
        choices = SettingsViewModel.STORAGE_CAP_CHOICES,
        selected = state.capBytes,
        label = { bytes(it) },
        onPick = viewModel::setStorageCap,
    )
    Text(
        stringResource(R.string.settings_storage_used, bytes(state.usedBytes), bytes(state.capBytes)) + " " +
            pluralStringResource(R.plurals.settings_storage_episodes, state.downloads, state.downloads),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    state.freeBytes?.let {
        Text(
            stringResource(R.string.settings_storage_free, bytes(it)),
            style = MaterialTheme.typography.bodyMedium,
            color = PodplayerTheme.colors.textQuiet,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AboutSection(appVersion: String, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    Heading(R.string.settings_about)
    Text(stringResource(R.string.settings_version, appVersion), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
    Text(
        stringResource(R.string.settings_license),
        style = MaterialTheme.typography.bodyMedium,
        color = PodplayerTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    TextButton(onClick = { context.openLink(MPL_URL) }, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) {
        Text(stringResource(R.string.settings_license_link))
    }
    LinkRow(R.string.settings_licenses, null, onOpenLicenses)
}

@Composable
internal fun Heading(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
    )
}

@Composable
private fun LinkRow(@StringRes title: Int, body: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PodplayerTheme.colors.textSecondary) }
    }
}

@Composable
private fun SwitchSetting(@StringRes title: Int, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = PodplayerTheme.colors.textSecondary)
        }
        // The whole row is the control; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting with a short list of values: the row shows the current one and opens a dialog to pick. */
@Composable
private fun <T> ChoiceSetting(
    @StringRes title: Int,
    value: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    body: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.choose)) { open = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = PodplayerTheme.colors.accent)
        body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PodplayerTheme.colors.textQuiet) }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(title)) },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        ChoiceRow(label(choice), selected = choice == selected) {
                            open = false
                            onPick(choice)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
