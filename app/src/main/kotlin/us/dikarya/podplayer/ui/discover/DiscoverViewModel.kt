/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.feed.opml.OpmlFeed
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.ImportSummary
import us.dikarya.podplayer.ui.browse.SubscribeOutcome

sealed interface SearchState {
    data object Idle : SearchState

    data object Loading : SearchState

    data class Results(val hits: List<SearchHit>) : SearchState

    data class Failed(val problem: DiscoverProblem) : SearchState
}

sealed interface PreviewState {
    data object None : PreviewState

    data object Loading : PreviewState

    class Ready(val preview: FeedPreview) : PreviewState

    data class Failed(val problem: DiscoverProblem) : PreviewState
}

sealed interface OpmlState {
    data object None : OpmlState

    data object Reading : OpmlState

    data class Ready(val title: String?, val feeds: List<OpmlFeed>, val skipped: Int) : OpmlState

    data object Importing : OpmlState

    data class Done(val summary: ImportSummary) : OpmlState

    data class Failed(val problem: DiscoverProblem) : OpmlState
}

data class DiscoverUiState(
    val query: String = "",
    val online: Boolean = true,
    val search: SearchState = SearchState.Idle,
    val preview: PreviewState = PreviewState.None,
    val opml: OpmlState = OpmlState.None,
    /** Subscribed feed URLs (canonical form) and their ids, so results show "Subscribed". */
    val subscribedUrls: Map<String, Long> = emptyMap(),
    val subscribing: Set<String> = emptySet(),
    /** A subscribe that didn't work, shown once. */
    val message: DiscoverProblem? = null,
    /** Set after subscribing from a preview: the screen opens this show, then calls [DiscoverViewModel.onShowOpened]. */
    val openShow: Long? = null,
)

/**
 * Discover (SPEC §12): one field for search terms or a feed URL, and OPML import. A typed or pasted URL subscribes
 * with `ownerTyped = true`; search results and OPML imports with false (CLAUDE.md, SPEC §3).
 */
class DiscoverViewModel(private val deps: BrowseDeps) : ViewModel() {
    private val local = MutableStateFlow(DiscoverUiState())
    private var work: Job? = null

    val state: StateFlow<DiscoverUiState> = combine(
        local,
        deps.online,
        deps.withRepositories<Map<String, Long>>(emptyMap()) { repos -> repos.feeds.feeds().map { feeds -> feeds.associate { it.url to it.id } } },
    ) { state, online, subscribed -> state.copy(online = online, subscribedUrls = subscribed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), DiscoverUiState())

    fun onQueryChange(text: String) = local.update { it.copy(query = text) }

    /** Searches, or previews the address typed. */
    fun submit() {
        work?.cancel()
        when (val input = DiscoverInput.of(local.value.query)) {
            DiscoverInput.Empty -> local.update { it.copy(search = SearchState.Idle, preview = PreviewState.None) }
            is DiscoverInput.Search -> {
                if (!deps.online.value) {
                    local.update { it.copy(search = SearchState.Failed(DiscoverProblem.Offline), preview = PreviewState.None) }
                    return
                }
                local.update { it.copy(search = SearchState.Loading, preview = PreviewState.None) }
                work = viewModelScope.launch {
                    val outcome = deps.search.search(input.term)
                    local.update {
                        it.copy(
                            search = when (outcome) {
                                is SearchOutcome.Results -> SearchState.Results(outcome.hits)
                                is SearchOutcome.Failed -> SearchState.Failed(outcome.problem)
                            },
                        )
                    }
                }
            }
            is DiscoverInput.FeedUrl -> {
                local.update { it.copy(preview = PreviewState.Loading, search = SearchState.Idle) }
                work = viewModelScope.launch {
                    val outcome = if (deps.online.value) deps.previewer.preview(input.url) else null
                    local.update {
                        it.copy(
                            preview = when (outcome) {
                                null -> PreviewState.Failed(DiscoverProblem.Offline)
                                is PreviewOutcome.Ready -> PreviewState.Ready(outcome.preview)
                                is PreviewOutcome.Failed -> PreviewState.Failed(outcome.problem)
                            },
                        )
                    }
                }
            }
        }
    }

    /** From search results: not owner-typed, so no private host is approved. */
    fun subscribe(hit: SearchHit) {
        if (hit.feedUrl in local.value.subscribing) return
        local.update { it.copy(subscribing = it.subscribing + hit.feedUrl) }
        viewModelScope.launch {
            val outcome = deps.subscriptions.subscribe(hit.feedUrl, ownerTyped = false)
            local.update { it.copy(subscribing = it.subscribing - hit.feedUrl, message = problemOf(outcome)) }
        }
    }

    /** The previewed address the owner typed: owner-typed, applied at once, then the show opens. */
    fun subscribePreview() {
        val preview = (local.value.preview as? PreviewState.Ready)?.preview ?: return
        if (preview.url in local.value.subscribing) return
        local.update { it.copy(subscribing = it.subscribing + preview.url) }
        viewModelScope.launch {
            val outcome = deps.subscriptions.subscribe(preview.url, ownerTyped = true, preview = preview.feed)
            val feedId = when (outcome) {
                is SubscribeOutcome.Subscribed -> outcome.feedId
                is SubscribeOutcome.AlreadySubscribed -> outcome.feedId
                else -> null
            }
            local.update {
                // The owner may have cancelled this preview and started another meanwhile: leave that one alone.
                val stillShown = (it.preview as? PreviewState.Ready)?.preview === preview
                if (feedId != null && stillShown) {
                    it.copy(subscribing = it.subscribing - preview.url, preview = PreviewState.None, query = "", openShow = feedId)
                } else if (feedId != null) {
                    it.copy(subscribing = it.subscribing - preview.url)
                } else {
                    it.copy(subscribing = it.subscribing - preview.url, message = problemOf(outcome))
                }
            }
        }
    }

    fun onShowOpened() = local.update { it.copy(openShow = null) }

    fun dismissPreview() = local.update { it.copy(preview = PreviewState.None) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /** A file the owner picked: read and previewed, nothing subscribed yet (SPEC §4). */
    fun opmlPicked(open: () -> InputStream?) {
        local.update { it.copy(opml = OpmlState.Reading) }
        viewModelScope.launch {
            val outcome = OpmlFile.read(open = open)
            local.update {
                it.copy(
                    opml = when (outcome) {
                        is OpmlOutcome.Ready -> OpmlState.Ready(outcome.title, outcome.feeds, outcome.skipped)
                        is OpmlOutcome.Failed -> OpmlState.Failed(outcome.problem)
                    },
                )
            }
        }
    }

    fun importOpml() {
        val ready = local.value.opml as? OpmlState.Ready ?: return
        local.update { it.copy(opml = OpmlState.Importing) }
        viewModelScope.launch {
            val opml = try {
                val summary = deps.subscriptions.importAll(ready.feeds)
                if (summary == null) OpmlState.Failed(DiscoverProblem.LibraryClosed) else OpmlState.Done(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the exception's type: its message could quote a feed address (a secret).
                deps.log.w(TAG, "OPML import stopped: a write failed (${e.javaClass.simpleName})")
                OpmlState.Failed(DiscoverProblem.CouldNotSave)
            }
            local.update { it.copy(opml = opml) }
        }
    }

    fun dismissOpml() = local.update { it.copy(opml = OpmlState.None) }

    private fun problemOf(outcome: SubscribeOutcome): DiscoverProblem? = when (outcome) {
        is SubscribeOutcome.Refused -> DiscoverProblem.BadUrl(outcome.problem)
        SubscribeOutcome.LibraryClosed -> DiscoverProblem.LibraryClosed
        else -> null
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
        const val TAG = "Discover"
    }
}
