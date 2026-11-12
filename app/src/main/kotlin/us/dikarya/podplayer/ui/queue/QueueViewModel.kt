/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.dikarya.podplayer.core.data.repo.QueueRepository
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

data class QueueUiState(val loading: Boolean = true, val closed: Boolean = false, val items: List<EpisodeItem> = emptyList())

/**
 * Up Next (SPEC §12). The database queue is the source of truth (CLAUDE.md); every change here is a database
 * change, and the playback engine rebuilds the player's playlist from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModel(private val deps: BrowseDeps) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)

    /** Changes go to the database one at a time, in the order the owner made them. */
    private val writes = Mutex()

    val state: StateFlow<QueueUiState> = deps.withRepositories(QueueUiState(loading = false, closed = true)) { repos ->
        repos.queue.episodes().flatMapLatest { episodes ->
            val ids = episodes.map { it.id }
            combine(
                repos.feeds.feeds().map { feeds -> feeds.associateBy { it.id } },
                repos.playback.records(ids.take(MAX_ROWS)),
                repos.media.observeAll().map { files -> files.associateBy { it.episodeId } },
                deps.playback.state,
            ) { feeds, records, media, playback ->
                QueueUiState(
                    loading = false,
                    items = episodes.take(MAX_ROWS).map { episode ->
                        EpisodeItem.of(episode, feeds[episode.feedId], records[episode.id], media[episode.id], queued = true, playback)
                    },
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), QueueUiState())

    fun moveUp(episodeId: Long) = write { it.shift(episodeId, -1) }

    fun moveDown(episodeId: Long) = write { it.shift(episodeId, 1) }

    /** A finished drag: one move to where the owner let go. */
    fun move(episodeId: Long, toIndex: Int) = write { it.move(episodeId, toIndex) }

    /** Right after the episode playing now (which playing put at the top), or first when nothing plays. */
    fun playNext(episodeId: Long) {
        val playing = deps.playback.state.value.episodeId
        write { it.playNext(episodeId, playing) }
    }

    fun remove(episodeId: Long) = write { it.remove(episodeId) }

    private fun write(block: suspend (QueueRepository) -> Unit) {
        viewModelScope.launch {
            writes.withLock {
                val queue = deps.repositories()?.queue ?: return@withLock
                block(queue)
            }
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L

        /** Well within `PlaybackRepository.MAX_IDS`; a longer queue shows its first 500. */
        const val MAX_ROWS = 500
    }
}
