/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

data class HomeUiState(
    val loading: Boolean = true,
    val libraryClosed: Boolean = false,
    /** The session's episode, else the last one started and not finished. */
    val continueItem: EpisodeItem? = null,
    val newEpisodes: List<EpisodeItem> = emptyList(),
    val shows: List<Feed> = emptyList(),
    /** Newest starred first; Home shows the row only when this isn't empty (SPEC §12). */
    val starred: List<EpisodeItem> = emptyList(),
    val online: Boolean = true,
    /** Older episodes exist beyond [newEpisodes]; Home asks for them as the list nears its end. */
    val canLoadMore: Boolean = false,
) {
    val noShows: Boolean get() = !loading && shows.isEmpty()
}

/** Home (SPEC §12). Playback is read from the session's state and changed only by commands. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val deps: BrowseDeps) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)

    private class Library(
        val feeds: List<Feed>,
        val latest: List<Episode>,
        val inProgress: Episode?,
        val loaded: Episode?,
        val starred: List<Episode>,
    )

    /** How many new episodes Home lists; grows a page at a time as the owner scrolls. */
    private val shown = MutableStateFlow(PAGE)

    /** Lists one more page of older episodes. */
    fun loadMore() {
        val listed = state.value
        if (listed.canLoadMore) shown.update { it + PAGE }
    }

    private fun library(repos: Repositories, shown: Int) = combine(
        // Every show, kept-aside ones too: the Continue card and Starred can hold their episodes.
        repos.feeds.allFeeds(),
        // One more than listed tells whether older episodes exist.
        repos.episodes.latest(shown + 1),
        repos.playback.inProgress(1).map { it.firstOrNull() },
        deps.playback.state.map { it.episodeId }.distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else repos.episodes.observe(id) },
        repos.starred.starred(limit = STARRED),
    ) { feeds, latest, inProgress, loaded, starred -> Library(feeds, latest, inProgress, loaded, starred) }

    val state: StateFlow<HomeUiState> = deps.withRepositories(HomeUiState(loading = false, libraryClosed = true)) { repos ->
        shown.flatMapLatest { count -> library(repos, count).map { count to it } }.flatMapLatest { (count, lib) ->
            val continueEpisode = lib.loaded ?: lib.inProgress
            val ids = (lib.latest.map { it.id } + listOfNotNull(continueEpisode?.id) + lib.starred.map { it.id }).distinct()
            combine(
                repos.playback.records(ids),
                repos.media.observeAll(),
                repos.queue.entries(),
                deps.playback.state,
                deps.online,
            ) { records, media, queue, playback, online ->
                val feeds = lib.feeds.associateBy { it.id }
                val downloads = media.associateBy { it.episodeId }
                val queued = queue.mapTo(HashSet()) { it.episodeId }
                fun item(e: Episode) = EpisodeItem.of(e, feeds[e.feedId], records[e.id], downloads[e.id], e.id in queued, playback)
                HomeUiState(
                    loading = false,
                    continueItem = continueEpisode?.let(::item),
                    // Started and played episodes stay in their place, shown dimmed (owner, 2026-11-18).
                    newEpisodes = lib.latest.take(count).map(::item),
                    // More candidates than listed: the database holds older ones.
                    canLoadMore = lib.latest.size > count,
                    shows = lib.feeds.filter { it.subscribed },
                    starred = lib.starred.map(::item),
                    online = online,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), HomeUiState())

    fun refresh() = deps.refresh(null)

    /** The refresh [refresh] asked for is running (pull-to-refresh's spinner). */
    val refreshing: Flow<Boolean> = deps.refreshRunning(null)

    companion object {
        /** New episodes per page. */
        const val PAGE = 30
        private const val STARRED = 12
        private const val STOP_AFTER_MS = 5_000L
    }
}
