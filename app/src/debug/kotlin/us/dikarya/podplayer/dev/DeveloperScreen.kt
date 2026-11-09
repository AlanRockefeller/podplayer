/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.dev

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.R
import us.dikarya.podplayer.appGraph
import us.dikarya.podplayer.core.data.db.DownloadLogEntity
import us.dikarya.podplayer.core.data.db.RefreshLogEntity
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.net.redact

/**
 * Debug builds only (SPEC §12): try feeds, refresh and downloads on a phone before the real UI exists (S8).
 * Add a feed by URL (as typed by the owner), refresh one or all, list episodes, download, cancel, delete,
 * and read the refresh and download logs. Everything shown from a feed is plain text.
 */
@Composable
fun DeveloperScreen() {
    val graph = LocalContext.current.appGraph
    val repos by produceState<Repositories?>(null) { value = graph.data.awaitRepositories() }
    val current = repos
    if (current == null) {
        Text(stringResource(R.string.dev_opening), modifier = Modifier.padding(16.dp))
        return
    }
    DeveloperContent(current)
}

@Composable
private fun DeveloperContent(repos: Repositories) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val feeds by repos.feeds.feeds().collectAsStateWithLifecycle(emptyList())
    val media by repos.media.observeAll().collectAsStateWithLifecycle(emptyList())
    val refreshLog by repos.logs.refreshLog(LOG_ROWS).collectAsStateWithLifecycle(emptyList())
    val downloadLog by repos.logs.downloadLog(LOG_ROWS).collectAsStateWithLifecycle(emptyList())
    val wifiOnly by graph.settings.downloadsWifiOnly.collectAsStateWithLifecycle(true)
    val cap by graph.settings.storageCapBytes.collectAsStateWithLifecycle(0L)
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val episodesFlow = remember(selected) { selected?.let { repos.episodes.forFeed(it) } ?: flowOf(emptyList()) }
    val episodes by episodesFlow.collectAsStateWithLifecycle(emptyList())
    var url by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val subscribedText = stringResource(R.string.dev_subscribed)
    val alreadyText = stringResource(R.string.dev_already_subscribed)
    val refusedText = stringResource(R.string.dev_refused)
    val mediaById = media.associateBy { it.episodeId }

    fun download(episode: Episode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch(Dispatchers.IO) { graph.jobs.downloads.enqueue(episode.id) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Heading(R.string.dev_add_feed)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.dev_feed_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = url.isNotBlank(),
                    onClick = {
                        scope.launch {
                            status = when (val result = repos.feeds.subscribe(url, ownerTyped = true)) {
                                is SubscribeResult.Subscribed -> {
                                    withContext(Dispatchers.IO) { graph.jobs.refreshNow(result.feedId) }
                                    selected = result.feedId
                                    url = ""
                                    subscribedText
                                }
                                is SubscribeResult.AlreadySubscribed -> {
                                    selected = result.feedId
                                    alreadyText
                                }
                                is SubscribeResult.Refused -> "$refusedText ${result.problem.name}"
                            }
                        }
                    },
                ) { Text(stringResource(R.string.dev_subscribe)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { graph.jobs.refreshNow(null) } }) {
                    Text(stringResource(R.string.dev_refresh_all))
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dev_wifi_only), modifier = Modifier.weight(1f))
                Switch(checked = wifiOnly, onCheckedChange = { value -> scope.launch { graph.settings.setDownloadsWifiOnly(value) } })
            }
            Text(
                stringResource(R.string.dev_storage, Formatter.formatFileSize(context, media.sumOf { it.bytesDownloaded }), Formatter.formatFileSize(context, cap)),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item { Heading(R.string.dev_feeds) }
        if (feeds.isEmpty()) item { Text(stringResource(R.string.dev_no_feeds)) }
        items(feeds, key = { "feed-${it.id}" }) { feed ->
            FeedRow(
                feed = feed,
                isSelected = feed.id == selected,
                onSelect = { selected = if (selected == feed.id) null else feed.id },
                onRefresh = { scope.launch(Dispatchers.IO) { graph.jobs.refreshNow(feed.id) } },
                onUnsubscribe = {
                    if (selected == feed.id) selected = null
                    scope.launch(Dispatchers.IO) { graph.jobs.downloads.removeFeed(feed.id) }
                },
            )
        }

        if (selected != null) {
            item { Heading(R.string.dev_episodes) }
            if (episodes.isEmpty()) item { Text(stringResource(R.string.dev_no_episodes)) }
            items(episodes, key = { "episode-${it.id}" }) { episode ->
                EpisodeRow(
                    episode = episode,
                    media = mediaById[episode.id],
                    onDownload = { download(episode) },
                    onCancel = { scope.launch(Dispatchers.IO) { graph.jobs.downloads.cancel(episode.id) } },
                    onDelete = { scope.launch(Dispatchers.IO) { graph.jobs.downloads.delete(episode.id) } },
                )
            }
        }

        logSection(R.string.dev_refresh_log, refreshLog, key = { "r-${it.id}" }) { RefreshLogRow(it, feeds) }
        logSection(R.string.dev_download_log, downloadLog, key = { "d-${it.id}" }) { DownloadLogRow(it) }
    }
}

private fun <T : Any> LazyListScope.logSection(title: Int, rows: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    item { Heading(title) }
    if (rows.isEmpty()) item { Text(stringResource(R.string.dev_log_empty)) }
    items(rows, key = key) { row(it) }
}

@Composable
private fun Heading(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun FeedRow(feed: Feed, isSelected: Boolean, onSelect: () -> Unit, onRefresh: () -> Unit, onUnsubscribe: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSelect).padding(vertical = 4.dp)) {
        Text(feed.title ?: redact(feed.url), style = MaterialTheme.typography.bodyLarge)
        Text(redact(feed.url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        feed.lastRefreshSuccessAt?.let { Text(stringResource(R.string.dev_last_success, time(it)), style = MaterialTheme.typography.bodySmall) }
        val errorAt = feed.lastRefreshErrorAt
        if (errorAt != null && errorAt > (feed.lastRefreshSuccessAt ?: 0)) {
            Text(
                stringResource(R.string.dev_last_error, time(errorAt), feed.lastRefreshError.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        feed.nextRefreshAt?.let { Text(stringResource(R.string.dev_next_try, time(it)), style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(onClick = onSelect) {
                Text(stringResource(if (isSelected) R.string.dev_hide_episodes else R.string.dev_show_episodes))
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.dev_refresh)) }
            TextButton(onClick = onUnsubscribe) { Text(stringResource(R.string.dev_unsubscribe)) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun EpisodeRow(episode: Episode, media: MediaFile?, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(episode.title ?: stringResource(R.string.dev_untitled), style = MaterialTheme.typography.bodyLarge)
        Text(
            time(episode.publishedAt) + if (episode.inFeed) "" else " · " + stringResource(R.string.dev_not_in_feed),
            style = MaterialTheme.typography.bodySmall,
        )
        val state = media?.state
        val bytes = Formatter.formatFileSize(context, media?.bytesDownloaded ?: 0)
        Text(
            when (state) {
                null -> stringResource(R.string.dev_not_downloaded)
                DownloadState.Complete -> stringResource(R.string.dev_state_complete, bytes, durationText(media?.probedDurationMs))
                else -> stringResource(R.string.dev_state_other, state.name, bytes)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val total = media?.totalBytes
        val done = media?.bytesDownloaded ?: 0L
        if (state == DownloadState.Downloading) {
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Row {
            when (state) {
                DownloadState.Queued, DownloadState.Downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.dev_cancel)) }
                DownloadState.Complete -> Unit
                else -> TextButton(onClick = onDownload) { Text(stringResource(R.string.dev_download)) }
            }
            if (state != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.dev_delete)) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RefreshLogRow(row: RefreshLogEntity, feeds: List<Feed>) {
    val name = feeds.firstOrNull { it.id == row.feedId }?.title ?: "#${row.feedId}"
    Column {
        Text("${time(row.at)} · ${row.outcome.name} · $name", style = MaterialTheme.typography.labelMedium)
        row.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DownloadLogRow(row: DownloadLogEntity) {
    Column {
        Text("${time(row.at)} · ${row.event.name} · #${row.episodeId}", style = MaterialTheme.typography.labelMedium)
        row.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun time(at: Long): String =
    DateUtils.formatDateTime(LocalContext.current, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)

private fun durationText(ms: Long?): String = ms?.let { DateUtils.formatElapsedTime(it / 1000) } ?: "?"

private const val LOG_ROWS = 50
