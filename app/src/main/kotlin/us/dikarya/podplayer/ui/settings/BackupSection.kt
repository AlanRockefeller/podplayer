/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.dikarya.podplayer.R
import us.dikarya.podplayer.appGraph
import us.dikarya.podplayer.core.data.DataState
import us.dikarya.podplayer.core.data.backup.BackupNames
import us.dikarya.podplayer.core.data.backup.BackupProblem
import us.dikarya.podplayer.core.data.backup.BackupReport
import us.dikarya.podplayer.core.data.backup.FolderOutcome
import us.dikarya.podplayer.core.data.backup.RestoreProblem
import us.dikarya.podplayer.core.data.backup.SafBackupFolder
import us.dikarya.podplayer.core.data.backup.StageResult
import us.dikarya.podplayer.core.data.settings.BackupFailure
import us.dikarya.podplayer.core.data.settings.BackupStatus
import us.dikarya.podplayer.restartApp
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** A short result shown under the buttons. */
private class Notice(@param:StringRes val text: Int, val isError: Boolean)

private fun BackupReport.notice(): Notice = when {
    problem == BackupProblem.DatabaseNotReady -> Notice(R.string.backup_not_ready, true)
    problem == BackupProblem.SnapshotFailed -> Notice(R.string.backup_failed_snapshot, true)
    problem != null -> Notice(R.string.backup_failed, true)
    ownerFolder == FolderOutcome.Failed -> Notice(R.string.backup_failed_folder, true)
    else -> Notice(R.string.backup_done, false)
}

@StringRes
internal fun RestoreProblem.text(): Int = when (this) {
    RestoreProblem.NotABackup -> R.string.restore_not_a_backup
    RestoreProblem.TooLarge -> R.string.restore_too_large
    RestoreProblem.NewerThanApp -> R.string.restore_newer
    RestoreProblem.Damaged -> R.string.restore_damaged
    RestoreProblem.UnexpectedContent -> R.string.restore_unexpected
    RestoreProblem.Unreadable -> R.string.restore_unreadable
    RestoreProblem.SafetyBackupFailed -> R.string.restore_safety_failed
}

internal fun formatTime(context: Context, at: Long): String = DateUtils.formatDateTime(
    context,
    at,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)

/**
 * Settings › Backup and restore (SPEC §7, minimal until M1-S9): the backup folder (Storage Access Framework,
 * persisted permission), back up now, export a file, restore from a file. Work runs in the app scope, so it
 * finishes even if the owner leaves the screen.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val graph = context.appGraph
    val data = graph.data
    val status by data.device.backupStatus.collectAsStateWithLifecycle(BackupStatus())
    val state by data.state.collectAsStateWithLifecycle()
    val ready = state is DataState.Ready
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }

    fun work(block: suspend () -> Notice?) {
        busy = true
        notice = null
        graph.appScope.launch(Dispatchers.IO) {
            val result = try {
                block()
            } catch (e: Exception) {
                graph.log.w("Backup", "Backup action failed", e)
                Notice(R.string.backup_failed, true)
            }
            notice = result
            busy = false
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            work {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val resolver = context.contentResolver
                status.folderUri?.let { old ->
                    if (old != uri.toString()) runCatching { resolver.releasePersistableUriPermission(old.toUri(), flags) }
                }
                resolver.takePersistableUriPermission(uri, flags)
                data.device.setBackupFolder(uri.toString(), SafBackupFolder.displayName(resolver, uri))
                data.backups.backupNow().notice()
            }
        }
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            work {
                val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("can't open the export file")
                val problem = out.use { data.backups.exportTo(it) }
                if (problem == null) Notice(R.string.backup_exported, false) else BackupReport(null, false, FolderOutcome.NotChosen, problem).notice()
            }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreFrom = uri }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_backup),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
        )
        val folderText = if (status.folderUri == null) {
            stringResource(R.string.backup_folder_none)
        } else {
            stringResource(R.string.backup_folder_chosen, status.folderName ?: stringResource(R.string.backup_folder_unnamed))
        }
        Text(folderText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            text = status.lastBackupAt?.let { stringResource(R.string.backup_last, formatTime(context, it)) } ?: stringResource(R.string.backup_never),
            style = MaterialTheme.typography.bodyMedium,
            color = PodplayerTheme.colors.textQuiet,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        status.lastFailure?.let { failure ->
            val text = when (failure) {
                BackupFailure.FolderUnreachable -> R.string.backup_failed_folder
                BackupFailure.SnapshotFailed -> R.string.backup_failed_snapshot
                BackupFailure.WriteFailed -> R.string.backup_failed
            }
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val folderLabel = if (status.folderUri == null) R.string.backup_choose_folder else R.string.backup_change_folder
            SettingsButton(folderLabel, enabled = !busy && ready) { pickFolder.launch(null) }
            SettingsButton(R.string.backup_now, enabled = !busy && ready) { work { data.backups.backupNow().notice() } }
            SettingsButton(R.string.backup_export, enabled = !busy && ready) {
                exportTo.launch(BackupNames.create(System.currentTimeMillis()))
            }
            SettingsButton(R.string.backup_restore, enabled = !busy) {
                pickBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            }
        }
        val shown = if (busy) Notice(R.string.backup_working, false) else notice
        shown?.let {
            Text(
                stringResource(it.text),
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    restoreFrom?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreFrom = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreFrom = null
                        work {
                            val result = data.restore { context.contentResolver.openInputStream(uri) ?: throw IOException("can't open the backup") }
                            when (result) {
                                is StageResult.Staged -> {
                                    context.restartApp()
                                    null
                                }
                                is StageResult.Refused -> Notice(result.problem.text(), true)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { restoreFrom = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingsButton(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}
