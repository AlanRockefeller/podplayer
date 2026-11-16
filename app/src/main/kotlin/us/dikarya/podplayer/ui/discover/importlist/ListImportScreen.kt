/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package us.dikarya.podplayer.ui.discover.importlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Discover › Import a list of shows (SPEC §12): paste or share in, review every match, then subscribe. */
@Composable
fun ListImportScreen(viewModel: ListImportViewModel, onOpenShow: (Long) -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.filePicked { context.contentResolver.openInputStream(uri) }
    }
    val summary = state.summary
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(state.online)
        when {
            summary != null -> Summary(summary, onDone)
            state.rows.isEmpty() -> Input(state, viewModel, onPickFile = { pickFile.launch(arrayOf("text/*", "application/json", "*/*")) })
            else -> Review(state, viewModel, onOpenShow)
        }
    }
}

@Composable
private fun Input(state: ListImportUiState, viewModel: ListImportViewModel, onPickFile: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.import_list_intro), style = MaterialTheme.typography.bodyMedium, color = PodplayerTheme.colors.textSecondary)
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            label = { Text(stringResource(R.string.import_list_field)) },
            minLines = 6,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth().testTag(TAG_IMPORT_FIELD),
        )
        state.problem?.let { Text(stringResource(problemText(it)), color = PodplayerTheme.colors.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::findShows, enabled = state.text.isNotBlank()) { Text(stringResource(R.string.import_list_find)) }
            OutlinedButton(onClick = onPickFile) { Text(stringResource(R.string.import_list_file)) }
        }
    }
}

@Composable
private fun Review(state: ListImportUiState, viewModel: ListImportViewModel, onOpenShow: (Long) -> Unit) {
    val colors = PodplayerTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pluralStringResource(R.plurals.import_list_will_subscribe, state.toSubscribe, state.toSubscribe, state.rows.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                state.leftOut?.let { left ->
                    val count = left.duplicates + left.tooLong + left.refused
                    if (count > 0) {
                        Text(pluralStringResource(R.plurals.import_list_left_out, count, count), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
                if (state.matching) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(stringResource(R.string.import_list_searching), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::confirm, enabled = state.toSubscribe > 0 && !state.subscribing, modifier = Modifier.testTag(TAG_IMPORT_CONFIRM)) {
                        Text(pluralStringResource(R.plurals.import_list_subscribe, state.toSubscribe, state.toSubscribe))
                    }
                    TextButton(onClick = viewModel::startOver, enabled = !state.subscribing) { Text(stringResource(R.string.import_list_start_over)) }
                }
                if (state.subscribing) CircularProgressIndicator()
            }
        }
        items(state.rows, key = { it.key }) { row -> RowCard(row, state.subscribedUrls, viewModel, onOpenShow) }
    }
}

@Composable
private fun RowCard(row: ImportRow, subscribed: Map<String, Long>, viewModel: ListImportViewModel, onOpenShow: (Long) -> Unit) {
    val colors = PodplayerTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (row.skipped) colors.surface else colors.surfaceElevated),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val hit = row.chosenHit
            when {
                row.skipped -> Text(stringResource(R.string.import_list_skipped), color = colors.textQuiet)
                row.status == RowStatus.Waiting -> Text(stringResource(R.string.import_list_waiting), color = colors.textQuiet)
                row.status == RowStatus.Searching -> Text(stringResource(R.string.import_list_searching_one), color = colors.textSecondary)
                row.status == RowStatus.NoMatch -> Text(stringResource(R.string.import_list_no_match), color = colors.warning)
                row.status == RowStatus.Failed -> Text(stringResource(R.string.import_list_failed), color = colors.error)
                hit != null -> {
                    Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    hit.publisher?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1) }
                    if (row.line is ImportLine.Address) Text(stringResource(R.string.import_list_address), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    if (!hit.isSecure) Text(stringResource(R.string.not_secure), style = MaterialTheme.typography.labelSmall, color = colors.warning)
                }
            }
            if (row.episodes.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.import_list_marks, row.episodes.size, row.episodes.size), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            val subscribedId = hit?.let { subscribed[it.feedUrl] }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (subscribedId != null && !row.skipped) {
                    TextButton(onClick = { onOpenShow(subscribedId) }) { Text(stringResource(R.string.discover_subscribed)) }
                }
                if (row.hits.size > 1 && !row.skipped) ChangeMenu(row, viewModel)
                if (row.status == RowStatus.Failed || row.status == RowStatus.NoMatch) {
                    TextButton(onClick = { viewModel.retry(row.key) }) { Text(stringResource(R.string.import_list_retry)) }
                }
                if (row.skipped) {
                    TextButton(onClick = { viewModel.include(row.key) }) { Text(stringResource(R.string.import_list_include)) }
                } else if (hit != null) {
                    TextButton(onClick = { viewModel.skip(row.key) }) { Text(stringResource(R.string.import_list_skip)) }
                }
            }
        }
    }
}

@Composable
private fun ChangeMenu(row: ImportRow, viewModel: ListImportViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.import_list_change)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            row.hits.forEachIndexed { i, hit ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            hit.publisher?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PodplayerTheme.colors.textSecondary, maxLines = 1) }
                        }
                    },
                    leadingIcon = { RadioButton(selected = row.chosen == i, onClick = null) },
                    onClick = {
                        open = false
                        viewModel.choose(row.key, i)
                    },
                )
            }
        }
    }
}

@Composable
private fun Summary(summary: ListImportSummary, onDone: () -> Unit) {
    val colors = PodplayerTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                stringResource(R.string.import_list_done, summary.added, summary.alreadySubscribed, summary.skipped, summary.refused),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (summary.notFound.isNotEmpty()) {
            item { Text(stringResource(R.string.import_list_not_found), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }) }
            items(summary.notFound) { Text("• $it", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        }
        if (summary.unmatchedEpisodes.isNotEmpty()) {
            item { Text(stringResource(R.string.import_list_unmatched_episodes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }) }
            items(summary.unmatchedEpisodes) { Text("• $it", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        }
        item { Button(onClick = onDone) { Text(stringResource(R.string.ok)) } }
    }
}

private fun problemText(problem: ListImportProblem): Int = when (problem) {
    ListImportProblem.Empty -> R.string.import_list_problem_empty
    ListImportProblem.TooMany -> R.string.import_list_problem_too_many
    ListImportProblem.TooLarge, ListImportProblem.FileTooLarge -> R.string.import_list_problem_too_large
    ListImportProblem.FileUnreadable -> R.string.import_list_problem_unreadable
    ListImportProblem.NotAnImportFile -> R.string.import_list_problem_not_import_file
    ListImportProblem.NewerFile -> R.string.import_list_problem_newer
    ListImportProblem.Offline -> R.string.import_list_problem_offline
}

const val TAG_IMPORT_FIELD = "import-field"
const val TAG_IMPORT_CONFIRM = "import-confirm"
