/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package us.dikarya.podplayer.ui.discover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.ImportSummary
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Discover (SPEC §12): search or paste a feed address in one field, or import an OPML file. */
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenShow: (Long) -> Unit,
    onImportList: () -> Unit = {},
    onPreview: (SearchHit) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val keyboard = LocalSoftwareKeyboardController.current
    val pickOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.opmlPicked { context.contentResolver.openInputStream(uri) }
    }
    LaunchedEffect(state.openShow) {
        state.openShow?.let {
            viewModel.onShowOpened()
            onOpenShow(it)
        }
    }
    val submit = {
        keyboard?.hide()
        viewModel.submit()
    }
    val isUrl = DiscoverInput.of(state.query) is DiscoverInput.FeedUrl

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { OfflineBanner(state.online) }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text(stringResource(R.string.discover_field)) },
                    supportingText = { Text(stringResource(R.string.discover_field_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(TAG_FIELD),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = submit, enabled = state.query.isNotBlank()) {
                        Text(stringResource(if (isUrl) R.string.discover_preview else R.string.discover_search))
                    }
                    OutlinedButton(onClick = { pickOpml.launch(OPML_TYPES) }) { Text(stringResource(R.string.discover_import_opml)) }
                    OutlinedButton(onClick = onImportList) { Text(stringResource(R.string.discover_import_list)) }
                }
                state.message?.let { problem ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(resources.message(problem), color = PodplayerTheme.colors.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }

        when (val preview = state.preview) {
            PreviewState.None -> Unit
            PreviewState.Loading -> item { Busy() }
            is PreviewState.Failed -> item { CenteredMessage(resources.message(preview.problem)) }
            is PreviewState.Ready -> item {
                PreviewCard(
                    preview = preview.preview,
                    subscribing = preview.preview.url in state.subscribing,
                    onSubscribe = viewModel::subscribePreview,
                    onCancel = viewModel::dismissPreview,
                )
            }
        }

        when (val search = state.search) {
            SearchState.Idle -> Unit
            SearchState.Loading -> item { Busy() }
            is SearchState.Failed -> item { CenteredMessage(resources.message(search.problem)) }
            is SearchState.Results -> {
                if (search.hits.isEmpty()) item { CenteredMessage(stringResource(R.string.discover_no_results)) }
                items(search.hits, key = { it.feedUrl }) { hit ->
                    val subscribedId = state.subscribedUrls[hit.feedUrl]
                    ResultRow(
                        hit = hit,
                        subscribedId = subscribedId,
                        subscribing = hit.feedUrl in state.subscribing,
                        onSubscribe = { viewModel.subscribe(hit) },
                        onOpen = onOpenShow,
                        onPreview = onPreview,
                    )
                }
            }
        }
    }

    OpmlDialog(state.opml, onImport = viewModel::importOpml, onDismiss = viewModel::dismissOpml)
}

@Composable
private fun Busy() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
}

@Composable
private fun PreviewCard(preview: FeedPreview, subscribing: Boolean, onSubscribe: () -> Unit, onCancel: () -> Unit) {
    val colors = PodplayerTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(TAG_PREVIEW),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The owner typed this address, so its host may be on the home network (SPEC §3).
                Artwork(preview.imageUrl, approvedHost = preview.approvedHost, size = 72.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(preview.title ?: stringResource(R.string.untitled_show), style = MaterialTheme.typography.titleMedium)
                    preview.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
                    Text(
                        pluralStringResourceSafe(R.plurals.discover_episode_count, preview.episodeCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            if (!preview.isSecure) Text(stringResource(R.string.not_secure_feed), style = MaterialTheme.typography.bodySmall, color = colors.warning)
            preview.latestTitles.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSubscribe, enabled = !subscribing) { Text(stringResource(R.string.action_subscribe)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** A plural whose only argument is its count. */
@Composable
private fun pluralStringResourceSafe(id: Int, count: Int): String = pluralStringResource(id, count, count)

@Composable
private fun ResultRow(
    hit: SearchHit,
    subscribedId: Long?,
    subscribing: Boolean,
    onSubscribe: () -> Unit,
    onOpen: (Long) -> Unit,
    onPreview: (SearchHit) -> Unit,
) {
    val colors = PodplayerTheme.colors
    // The whole row opens the show: its page when subscribed, else a preview with its episodes (owner, 2026-11-18).
    val open = if (subscribedId != null) {
        Modifier.clickable(role = Role.Button) { onOpen(subscribedId) }
    } else {
        Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.discover_open_preview, hit.title)) { onPreview(hit) }
    }
    Row(Modifier.fillMaxWidth().then(open).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Search results' art comes from Apple's image servers, through the gate and the sandbox like any image.
        Artwork(hit.artworkUrl, approvedHost = null, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hit.publisher?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (!hit.isSecure) Text(stringResource(R.string.not_secure), style = MaterialTheme.typography.labelSmall, color = colors.warning)
        }
        when {
            subscribedId != null -> TextButton(onClick = { onOpen(subscribedId) }) { Text(stringResource(R.string.discover_subscribed)) }
            else -> OutlinedButton(onClick = onSubscribe, enabled = !subscribing) { Text(stringResource(R.string.action_subscribe)) }
        }
    }
}

@Composable
private fun OpmlDialog(state: OpmlState, onImport: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    when (state) {
        OpmlState.None -> Unit
        OpmlState.Reading, OpmlState.Importing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.opml_title)) },
            text = { Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() } },
            confirmButton = {},
        )
        is OpmlState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.opml_title)) },
            text = { Text(resources.message(state.problem)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        is OpmlState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.opml_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pluralStringResourceSafe(R.plurals.opml_found, state.feeds.size))
                    if (state.skipped > 0) Text(pluralStringResourceSafe(R.plurals.opml_skipped, state.skipped), color = PodplayerTheme.colors.textSecondary)
                    state.feeds.take(OPML_PREVIEW_ROWS).forEach { feed ->
                        Text("• " + (AppleSearch.clean(feed.title) ?: stringResource(R.string.untitled_show)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val more = state.feeds.size - OPML_PREVIEW_ROWS
                    if (more > 0) Text(pluralStringResourceSafe(R.plurals.opml_more, more), color = PodplayerTheme.colors.textSecondary)
                }
            },
            confirmButton = { TextButton(onClick = onImport, enabled = state.feeds.isNotEmpty()) { Text(stringResource(R.string.opml_import)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        is OpmlState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.opml_title)) },
            text = { Text(summaryText(state.summary)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun summaryText(summary: ImportSummary): String = stringResource(R.string.opml_done, summary.added, summary.alreadySubscribed, summary.refused)

/** OPML files come with all sorts of types, so the picker offers everything; the parser decides. */
private val OPML_TYPES = arrayOf("*/*")
private const val OPML_PREVIEW_ROWS = 20
const val TAG_FIELD = "discover-field"
const val TAG_PREVIEW = "discover-preview"
