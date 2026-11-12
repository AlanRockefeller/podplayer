/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.data.settings.LibraryLayout
import us.dikarya.podplayer.core.data.settings.LibrarySort
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.ui.browse.BrowseDeps

data class ShowItem(val feed: Feed, val newestEpisodeAt: Long?)

data class LibraryUiState(
    val loading: Boolean = true,
    val libraryClosed: Boolean = false,
    val shows: List<ShowItem> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.Grid,
    val sort: LibrarySort = LibrarySort.Title,
    val online: Boolean = true,
)

/** Library (SPEC §12): the subscriptions as a grid or a list, sorted; both choices are settings. */
class LibraryViewModel(private val deps: BrowseDeps) : ViewModel() {
    val state: StateFlow<LibraryUiState> = deps.withRepositories(LibraryUiState(loading = false, libraryClosed = true)) { repos ->
        combine(
            repos.feeds.feeds(),
            repos.feeds.newestEpisodeDates(),
            deps.settings.libraryLayout,
            deps.settings.librarySort,
            deps.online,
        ) { feeds, newest, layout, sort, online ->
            val shows = feeds.map { ShowItem(it, newest[it.id]) }
            LibraryUiState(loading = false, shows = sorted(shows, sort), layout = layout, sort = sort, online = online)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), LibraryUiState())

    fun setLayout(layout: LibraryLayout) {
        viewModelScope.launch { deps.settings.setLibraryLayout(layout) }
    }

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { deps.settings.setLibrarySort(sort) }
    }

    fun refreshAll() = deps.refresh(null)

    companion object {
        fun sorted(shows: List<ShowItem>, sort: LibrarySort): List<ShowItem> = when (sort) {
            LibrarySort.Title -> shows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.feed.title.orEmpty() })
            LibrarySort.NewestEpisode -> shows.sortedWith(compareByDescending<ShowItem> { it.newestEpisodeAt ?: Long.MIN_VALUE }.thenBy { it.feed.id })
            LibrarySort.Subscribed -> shows.sortedWith(compareByDescending<ShowItem> { it.feed.subscribedAt }.thenByDescending { it.feed.id })
        }

        private const val STOP_AFTER_MS = 5_000L
    }
}
