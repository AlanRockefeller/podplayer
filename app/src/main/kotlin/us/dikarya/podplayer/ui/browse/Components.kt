/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** "You're offline" with what still works (M1-S8: downloads stay playable). */
@Composable
fun OfflineBanner(online: Boolean, modifier: Modifier = Modifier) {
    if (online) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PodplayerTheme.colors.surfaceElevated)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_offline), contentDescription = null, tint = PodplayerTheme.colors.warning)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.bodyMedium, color = PodplayerTheme.colors.textPrimary)
    }
}

@Composable
fun SectionHeading(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = PodplayerTheme.colors.textPrimary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = PodplayerTheme.colors.textSecondary, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** The library database couldn't be opened; DataStateDialogs offers the restore. */
@Composable
fun LibraryClosed() = CenteredMessage(stringResource(R.string.library_closed))

/** "12 Jun 2026 · 45 min · 20 min left", or "Played". */
@Composable
fun episodeMeta(item: EpisodeItem, withShow: Boolean): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val parts = buildList {
        if (withShow) item.showTitle?.let(::add)
        add(DateUtils.formatDateTime(context, item.episode.publishedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))
        item.durationMs?.let { add(durationText(resources, it)) }
        when {
            item.played -> add(resources.getString(R.string.episode_played))
            item.progress != null -> item.durationMs?.let { add(resources.getString(R.string.episode_left, durationText(resources, it - item.positionMs))) }
        }
    }
    return parts.joinToString(" · ")
}

/**
 * Owner, 2026-11-18: episode titles were too big and show names too small. Titles in lists take this size; the
 * show's name gets its own line ([ShowNameLine]) at a bigger size than the date line.
 */
val EpisodeTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium

/** The show's name above an episode title: bigger than the date line, on one line. Nothing for null. */
@Composable
fun ShowNameLine(name: String?, modifier: Modifier = Modifier) {
    name ?: return
    Text(name, style = MaterialTheme.typography.titleSmall, color = PodplayerTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

fun durationText(resources: Resources, ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) + 30_000) / 60_000
    return if (minutes >= 60) {
        resources.getString(R.string.duration_hours, minutes / 60, minutes % 60)
    } else {
        resources.getString(R.string.duration_minutes, minutes)
    }
}

/** One compact episode row (SPEC §12 Home "New episodes"; the show page's list): star, play, queue, download. */
@Composable
fun EpisodeRow(
    item: EpisodeItem,
    withShow: Boolean,
    onOpen: () -> Unit,
    actions: EpisodeActions,
    onDownload: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Leaves out star and queue (they are on the episode page), so the title gets the room. */
    compact: Boolean = false,
) {
    val colors = PodplayerTheme.colors
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        val title = EpisodeTitleStyle
        // At large font sizes the buttons move under the title rather than squeeze it (see ActionsBeside).
        ActionsBeside(
            minMainWidth = textRoom(title.fontSize, MIN_TITLE_EMS, extra = ROW_ARTWORK + 16.dp + 12.dp),
            modifier = Modifier.padding(end = 4.dp, top = 8.dp, bottom = 8.dp),
            main = {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(item.imageUrl, item.approvedHost, ROW_ARTWORK)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // The show above its episode (owner, 2026-11-18).
                        if (withShow) ShowNameLine(item.showTitle)
                        Text(
                            item.episode.title ?: stringResource(R.string.untitled_episode),
                            style = title,
                            // Started counts as played here (owner, 2026-11-18): it stays in its place, dimmed.
                            color = if (item.started) colors.textQuiet else colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(episodeMeta(item, withShow = false), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = {
                if (compact) {
                    // Play and Download only, drawn closer together: the 48 dp touch targets overlap a little, the icons
                    // keep 12 dp apart (owner, 2026-11-16).
                    Row(horizontalArrangement = Arrangement.spacedBy(-COMPACT_OVERLAP)) {
                        PlayButton(item, actions)
                        DownloadButton(item, actions, onDownload)
                    }
                } else {
                    StarButton(item, actions)
                    PlayButton(item, actions)
                    QueueButton(item, actions)
                    DownloadButton(item, actions, onDownload)
                }
            },
        )
        item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
    }
}

@Composable
fun PlayButton(item: EpisodeItem, actions: EpisodeActions) {
    val title = item.episode.title.orEmpty()
    IconButton(onClick = { actions.togglePlay(item) }) {
        Icon(
            painterResource(if (item.playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = stringResource(if (item.playing) R.string.action_pause_episode else R.string.action_play_episode, title),
            tint = PodplayerTheme.colors.accent,
        )
    }
}

@Composable
fun QueueButton(item: EpisodeItem, actions: EpisodeActions) {
    val title = item.episode.title.orEmpty()
    IconButton(onClick = { actions.toggleQueue(item) }) {
        Icon(
            painterResource(if (item.queued) R.drawable.ic_queue_added else R.drawable.ic_queue_add),
            contentDescription = stringResource(if (item.queued) R.string.action_unqueue_episode else R.string.action_queue_episode, title),
        )
    }
}

/** Download; while downloading, a progress ring that cancels; when done, a check (deleting is on the episode page). */
@Composable
fun DownloadButton(item: EpisodeItem, actions: EpisodeActions, onDownload: (Long) -> Unit) {
    val title = item.episode.title.orEmpty()
    when (item.downloadState) {
        DownloadState.Complete -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_download_done),
                contentDescription = stringResource(R.string.state_downloaded, title),
                tint = PodplayerTheme.colors.accentSecondary,
            )
        }
        DownloadState.Queued, DownloadState.Downloading -> IconButton(onClick = { actions.cancelDownload(item.id) }) {
            val total = item.download?.totalBytes
            val done = item.download?.bytesDownloaded ?: 0
            val description = stringResource(R.string.action_cancel_download, title)
            val ring = Modifier.size(24.dp).semantics { contentDescription = description }
            if (total != null && total > 0 && item.downloadState == DownloadState.Downloading) {
                CircularProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = ring)
            } else {
                CircularProgressIndicator(modifier = ring)
            }
        }
        else -> IconButton(onClick = { onDownload(item.id) }) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.action_download_episode, title))
        }
    }
}

/**
 * Starts a download, first asking for the notification permission on Android 13+ (the progress notification);
 * downloads run either way (as in the Developer screen).
 */
@Composable
fun rememberDownloadStarter(actions: EpisodeActions): (Long) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return { id ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        actions.download(id)
    }
}

/** The "Find a show" button on empty screens. */
@Composable
fun FindShowsButton(onDiscover: () -> Unit) {
    OutlinedButton(onClick = onDiscover) { Text(stringResource(R.string.find_shows)) }
}

/** How far the compact row's two buttons overlap. */
private val COMPACT_OVERLAP = 12.dp
private val ROW_ARTWORK = 48.dp
