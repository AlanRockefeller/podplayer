/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import us.dikarya.podplayer.core.data.repo.PlaybackRepository
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

data class StarredUiState(
    val loading: Boolean = true,
    val libraryClosed: Boolean = false,
    val query: String = "",
    val items: List<EpisodeItem> = emptyList(),
    val online: Boolean = true,
) {
    /** Nothing starred at all (as opposed to a search with no matches). */
    val nothingStarred: Boolean get() = !loading && items.isEmpty() && query.isBlank()
    val noMatches: Boolean get() = !loading && items.isEmpty() && query.isNotBlank()
}

/** Library › Starred (SPEC §12): newest starred first, searchable by episode or show name, shareable as text. */
@OptIn(ExperimentalCoroutinesApi::class)
class StarredViewModel(private val deps: BrowseDeps) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)
    private val query = MutableStateFlow("")

    val state: StateFlow<StarredUiState> = deps.withRepositories(StarredUiState(loading = false, libraryClosed = true)) { repos ->
        query.flatMapLatest { q ->
            repos.starred.starred(q).flatMapLatest { episodes ->
                val ids = episodes.map { it.id }.take(PlaybackRepository.MAX_IDS)
                combine(
                    repos.feeds.allFeeds().map { feeds -> feeds.associateBy { it.id } },
                    repos.playback.records(ids),
                    repos.media.observeAll(),
                    repos.queue.entries(),
                    combine(deps.playback.state, deps.online) { playback, online -> playback to online },
                ) { feeds, records, media, queue, (playback, online) ->
                    val downloads = media.associateBy { it.episodeId }
                    val queued = queue.mapTo(HashSet()) { it.episodeId }
                    StarredUiState(
                        loading = false,
                        query = q,
                        items = episodes.map { e -> EpisodeItem.of(e, feeds[e.feedId], records[e.id], downloads[e.id], e.id in queued, playback) },
                        online = online,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), StarredUiState())

    /** The field shows [text] at once; the list follows the search. */
    val queryText: StateFlow<String> = query

    fun onQueryChange(text: String) {
        query.value = text.take(MAX_QUERY)
    }

    /** What "Share starred" sends: the episodes listed now (the search applies). */
    fun shareText(heading: String, untitled: String): String =
        StarredShare.text(heading, untitled, state.value.items.map { it.episode to it.showTitle })

    private companion object {
        const val STOP_AFTER_MS = 5_000L
        const val MAX_QUERY = 200
    }
}
