/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.model.PositionCause
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.player.clockText
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** The FileProvider that shares only the export folder (AndroidManifest.xml, res/xml/log_export_paths.xml). */
fun logExportAuthority(context: Context) = "${context.packageName}.logexport"

@StringRes
private fun LogTab.label(): Int = when (this) {
    LogTab.App -> R.string.logs_tab_app
    LogTab.Playback -> R.string.logs_tab_playback
    LogTab.Downloads -> R.string.logs_tab_downloads
    LogTab.Refresh -> R.string.logs_tab_refresh
    LogTab.Crashes -> R.string.logs_tab_crashes
}

@StringRes
private fun PositionCause.label(): Int = when (this) {
    PositionCause.Seek -> R.string.logs_cause_seek
    PositionCause.SkipBack -> R.string.logs_cause_skip_back
    PositionCause.SkipForward -> R.string.logs_cause_skip_forward
    PositionCause.Restore -> R.string.logs_cause_restore
    PositionCause.RewindAfterPause -> R.string.logs_cause_rewind
    PositionCause.NextEpisode -> R.string.logs_cause_next
    PositionCause.SwitchToFile -> R.string.logs_cause_to_file
    PositionCause.FallBackToStream -> R.string.logs_cause_to_stream
    PositionCause.Player -> R.string.logs_cause_player
    PositionCause.Unexplained -> R.string.logs_cause_unexplained
    PositionCause.SilentRecovery -> R.string.logs_cause_silent_recovery
}

@StringRes
private fun DownloadEvent.label(): Int = when (this) {
    DownloadEvent.Queued -> R.string.logs_event_queued
    DownloadEvent.Started -> R.string.logs_event_started
    DownloadEvent.Resumed -> R.string.logs_event_resumed
    DownloadEvent.Restarted -> R.string.logs_event_restarted
    DownloadEvent.Completed -> R.string.logs_event_completed
    DownloadEvent.Failed -> R.string.logs_event_failed
    DownloadEvent.Cancelled -> R.string.logs_event_cancelled
    DownloadEvent.Deleted -> R.string.logs_event_deleted
}

@StringRes
private fun RefreshOutcome.label(): Int = when (this) {
    RefreshOutcome.Updated -> R.string.logs_outcome_updated
    RefreshOutcome.NotModified -> R.string.logs_outcome_not_modified
    RefreshOutcome.Failed -> R.string.logs_outcome_failed
}

/**
 * Settings › Logs (SPEC §12): the app, playback, download, refresh and crash logs, newest first, and Export, which
 * writes one redacted file and opens the share sheet. Nothing is written or shared before Export is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var noShareApp by remember { mutableStateOf(false) }
    val export = state.export
    LaunchedEffect(export) {
        if (export is ExportState.Ready) {
            // FileProvider reads the disk (it resolves the cache folder): off the main thread, or debug builds'
            // StrictMode stops the app, as it did on the owner's phone (2026-11-12).
            val uri = withContext(Dispatchers.IO) { FileProvider.getUriForFile(context, logExportAuthority(context), export.file) }
            noShareApp = !share(context, uri)
            viewModel.exportShared()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.logs_export_body),
            style = MaterialTheme.typography.bodyMedium,
            color = PodplayerTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button(
            onClick = viewModel::export,
            enabled = export != ExportState.Working,
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.logs_export)) }
        val notice = when {
            export == ExportState.Working -> R.string.logs_exporting
            export == ExportState.Failed -> R.string.logs_export_failed
            noShareApp -> R.string.logs_no_share_app
            else -> null
        }
        notice?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = if (export == ExportState.Working) PodplayerTheme.colors.textSecondary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal, modifier = Modifier.padding(top = 8.dp)) {
            LogTab.entries.forEach { tab ->
                Tab(selected = tab == state.tab, onClick = { viewModel.select(tab) }, text = { Text(stringResource(tab.label())) })
            }
        }
        when {
            state.loading -> Loading()
            state.entries.isEmpty() -> CenteredMessage(stringResource(R.string.logs_empty))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries) { entry ->
                    Entry(entry, state.tab)
                    HorizontalDivider(color = PodplayerTheme.colors.divider)
                }
            }
        }
    }
}

@Composable
private fun Entry(entry: LogEntry, tab: LogTab) {
    val context = LocalContext.current
    val colors = PodplayerTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val head = listOfNotNull(entry.at?.let { formatTime(context, it) }, entry.subject).joinToString(" · ")
        if (head.isNotEmpty()) Text(head, style = MaterialTheme.typography.labelMedium, color = colors.textQuiet)
        val cause = entry.cause
        val event = entry.downloadEvent
        val outcome = entry.refreshOutcome
        when {
            cause != null -> {
                val source = stringResource(if (entry.streaming == true) R.string.logs_from_stream else R.string.logs_from_file)
                Text(stringResource(cause.label()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.logs_position, clockText(entry.fromMs ?: 0), clockText(entry.toMs ?: 0)) + " ($source)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            event != null -> {
                Text(stringResource(event.label()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (entry.text.isNotEmpty()) Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            outcome != null -> {
                Text(
                    stringResource(outcome.label()) + " · " + stringResource(R.string.logs_refresh_counts, entry.newEpisodes, entry.updatedEpisodes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (entry.text.isNotEmpty()) Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            tab == LogTab.Crashes -> Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            else -> Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The share sheet for the export file, readable only by the app the owner picks. False if nothing can take it. */
private fun share(context: Context, uri: Uri): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, context.getString(R.string.logs_share_title))
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
