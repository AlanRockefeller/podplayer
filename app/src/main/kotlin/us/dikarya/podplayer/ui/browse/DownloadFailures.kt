/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.db.DownloadEvent

/** A download that failed: the episode's title (null when untitled) and the log's plain reason (already redacted). */
data class DownloadFailure(val episodeTitle: String?, val reason: String?)

/**
 * Downloads that fail at or after [since] (epoch ms), once each, oldest first. Found on the owner's phone
 * (2026-11-18): a refused download just went back to its Download button, the reason only in Settings › Logs.
 */
fun downloadFailures(deps: BrowseDeps, since: Long): Flow<DownloadFailure> = flow {
    val repos = deps.repositories() ?: return@flow
    val reported = HashSet<Long>()
    repos.logs.downloadLog(RECENT_ROWS).collect { rows ->
        for (row in rows.asReversed()) {
            if (row.event != DownloadEvent.Failed || row.at < since || !reported.add(row.id)) continue
            emit(DownloadFailure(repos.episodes.get(row.episodeId)?.title, row.message))
        }
    }
}

/** Download events a burst can add between two looks at the log; more than one refresh's auto-downloads add. */
private const val RECENT_ROWS = 50

/** Shows each download that fails while the app is open as a snackbar with its reason. */
@Composable
fun DownloadFailureBar(deps: BrowseDeps) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val since = remember { System.currentTimeMillis() }
    LaunchedEffect(deps, snackbars) {
        downloadFailures(deps, since).collect { failure ->
            val title = failure.episodeTitle ?: resources.getString(R.string.untitled_episode)
            val message = failure.reason?.let { resources.getString(R.string.download_failed_because, title, it) }
                ?: resources.getString(R.string.download_failed, title)
            snackbars.showSnackbar(message = message, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
}
