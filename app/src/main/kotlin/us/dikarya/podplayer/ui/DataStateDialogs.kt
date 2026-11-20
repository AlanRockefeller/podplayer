/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.dikarya.podplayer.R
import us.dikarya.podplayer.appGraph
import us.dikarya.podplayer.core.data.DataState
import us.dikarya.podplayer.core.data.OpenProblem
import us.dikarya.podplayer.core.data.backup.RestoreProblem
import us.dikarya.podplayer.core.data.backup.StageResult
import us.dikarya.podplayer.restartApp
import us.dikarya.podplayer.ui.settings.formatTime
import us.dikarya.podplayer.ui.settings.text

/**
 * What the owner sees when the start-up check finds a problem (SPEC §7): a damaged database offers the newest
 * backup that passes validation, or an empty library (the damaged file is kept either way).
 */
@Composable
fun DataStateDialogs() {
    val context = LocalContext.current
    val graph = context.appGraph
    val state by graph.data.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<RestoreProblem?>(null) }
    var startFreshFailed by remember { mutableStateOf(false) }
    var dismissedFailure by remember { mutableStateOf(false) }

    when (val current = state) {
        is DataState.Damaged -> {
            val newest = current.backups.firstOrNull()
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.db_damaged_title)) },
                text = {
                    val body = if (newest == null) {
                        stringResource(R.string.db_damaged_body_none)
                    } else {
                        stringResource(R.string.db_damaged_body_backup, formatTime(context, newest.createdAt))
                    }
                    val failed = if (startFreshFailed) stringResource(R.string.db_damaged_start_fresh_failed) + "\n\n" else ""
                    Text(failed + (problem?.let { stringResource(it.text()) + "\n\n" + body } ?: body))
                },
                confirmButton = {
                    if (newest != null) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                graph.appScope.launch(Dispatchers.IO) {
                                    // Newest first; a backup that fails validation falls through to the next.
                                    var last: RestoreProblem? = null
                                    for (candidate in current.backups) {
                                        when (val result = graph.data.restore(candidate)) {
                                            is StageResult.Staged -> {
                                                context.restartApp()
                                                return@launch
                                            }
                                            is StageResult.Refused -> last = result.problem
                                        }
                                    }
                                    problem = last
                                    busy = false
                                }
                            },
                        ) { Text(stringResource(R.string.db_damaged_restore)) }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            graph.appScope.launch(Dispatchers.IO) {
                                try {
                                    // False: the damaged file couldn't be kept, so nothing was emptied.
                                    startFreshFailed = !graph.data.startFresh()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    graph.log.e("Data", "Starting with an empty library failed", e)
                                    startFreshFailed = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.db_damaged_start_fresh)) }
                },
            )
        }
        is DataState.Failed -> if (!dismissedFailure) {
            val body = when (current.problem) {
                OpenProblem.NewerThanApp -> R.string.db_failed_newer
                OpenProblem.BackupBeforeMigrationFailed -> R.string.db_failed_backup
                OpenProblem.CouldNotOpen -> R.string.db_failed_open
            }
            AlertDialog(
                onDismissRequest = { dismissedFailure = true },
                title = { Text(stringResource(R.string.db_problem_title)) },
                text = { Text(stringResource(body)) },
                confirmButton = { TextButton(onClick = { dismissedFailure = true }) { Text(stringResource(R.string.ok)) } },
            )
        }
        else -> Unit
    }
}
