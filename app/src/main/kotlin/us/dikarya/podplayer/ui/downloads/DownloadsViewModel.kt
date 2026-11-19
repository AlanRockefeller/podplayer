/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

data class DownloadsUiState(
    val loading: Boolean = true,
    val libraryClosed: Boolean = false,
    val items: List<EpisodeItem> = emptyList(),
    /** Finished downloads: they play with no network. */
    val readyCount: Int = 0,
    val readyBytes: Long = 0,
    val online: Boolean = true,
) {
    val empty: Boolean get() = !loading && items.isEmpty()
}

/**
 * Library › Downloads (owner, 2026-11-18): everything on the phone for listening offline, newest first, after any
 * downloads still on their way. Each row plays, queues and deletes like anywhere else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(private val deps: BrowseDeps) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)

    val state: StateFlow<DownloadsUiState> = deps.withRepositories(DownloadsUiState(loading = false, libraryClosed = true)) { repos ->
        repos.episodes.downloaded().flatMapLatest { episodes ->
            combine(
                repos.feeds.allFeeds().map { feeds -> feeds.associateBy { it.id } },
                // Every listed row's record, past the 500 an id list allows (Codex review, PR #28).
                repos.playback.recordsForDownloads(),
                repos.media.observeAll(),
                repos.queue.entries(),
                combine(deps.playback.state, deps.online) { playback, online -> playback to online },
            ) { feeds, records, media, queue, (playback, online) ->
                val downloads = media.associateBy { it.episodeId }
                val queued = queue.mapTo(HashSet()) { it.episodeId }
                val ready = media.filter { it.state == DownloadState.Complete }
                DownloadsUiState(
                    loading = false,
                    items = episodes.map { e -> EpisodeItem.of(e, feeds[e.feedId], records[e.id], downloads[e.id], e.id in queued, playback) },
                    readyCount = ready.size,
                    readyBytes = ready.sumOf { it.bytesDownloaded },
                    online = online,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), DownloadsUiState())

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
