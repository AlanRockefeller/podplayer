/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import us.dikarya.podplayer.core.feed.notes.NotesProblem
import us.dikarya.podplayer.core.feed.notes.NotesResult
import us.dikarya.podplayer.core.feed.notes.RichText
import us.dikarya.podplayer.core.feed.notes.ShowNotes
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

sealed interface NotesState {
    data object Loading : NotesState

    data class Ready(val text: RichText) : NotesState

    /** Over the sanitizer's limits: a plain message instead (CLAUDE.md: reject, log why). */
    data class Rejected(val problem: NotesProblem) : NotesState
}

data class EpisodeUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    val item: EpisodeItem? = null,
    val notes: NotesState = NotesState.Loading,
    val online: Boolean = true,
)

/** An episode's page (SPEC §12). Notes are sanitized off the main thread, with the episode's length for timestamps. */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModel(private val deps: BrowseDeps, private val episodeId: Long) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)

    val state: StateFlow<EpisodeUiState> = deps.withRepositories(EpisodeUiState(loading = false, gone = true)) { repos ->
        repos.episodes.observe(episodeId).flatMapLatest { episode ->
            if (episode == null) return@flatMapLatest flowOf(EpisodeUiState(loading = false, gone = true))
            val notes = flowOf(episode.notes to episode.durationMs).distinctUntilChanged()
                .mapLatest { (html, duration) -> sanitize(html, duration) }
                .flowOn(Dispatchers.Default)
                .onStart { emit(NotesState.Loading) }
            val item = combine(
                repos.feeds.feed(episode.feedId),
                repos.playback.record(episodeId),
                repos.media.observe(episodeId),
                repos.queue.entries().map { q -> q.any { it.episodeId == episodeId } }.distinctUntilChanged(),
                deps.playback.state,
            ) { feed, record, media, queued, playback -> EpisodeItem.of(episode, feed, record, media, queued, playback) }
            combine(item, notes, deps.online) { i, n, online -> EpisodeUiState(loading = false, item = i, notes = n, online = online) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), EpisodeUiState())

    /** A timestamp in the notes (SPEC §5): this episode from there, through the session. */
    fun playAt(positionMs: Long) = actions.playAt(episodeId, positionMs)

    private fun sanitize(html: String?, durationMs: Long?): NotesState = when (val result = ShowNotes.sanitize(html, durationMs)) {
        is NotesResult.Ok -> NotesState.Ready(result.text)
        is NotesResult.Rejected -> {
            deps.log.w(TAG, "Episode $episodeId: show notes refused (${result.problem})")
            NotesState.Rejected(result.problem)
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
        const val TAG = "Notes"
    }
}
