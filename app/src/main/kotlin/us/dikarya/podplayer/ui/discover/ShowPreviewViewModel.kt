/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.feed.notes.NotesResult
import us.dikarya.podplayer.core.feed.notes.RichText
import us.dikarya.podplayer.core.feed.notes.ShowNotes
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.SubscribeOutcome

/** One episode as the preview lists it: nothing to play yet, just what it is. */
data class PreviewEpisode(val title: String?, val publishedAt: Long?, val durationMs: Long?)

sealed interface ShowPreviewState {
    data object Loading : ShowPreviewState

    data class Ready(
        val title: String?,
        val author: String?,
        /** Sanitized (SPEC §5); null when there is none. */
        val description: RichText?,
        /** Episodes with audio, in the feed's order (newest first for nearly every feed). */
        val episodes: List<PreviewEpisode>,
        val isSecure: Boolean,
        val subscribing: Boolean = false,
        /** A subscribe that didn't work, shown once. */
        val message: DiscoverProblem? = null,
    ) : ShowPreviewState

    data class Failed(val problem: DiscoverProblem) : ShowPreviewState
}

/**
 * A search result's show before subscribing (owner, 2026-11-18). The feed is fetched once, when the owner opens the
 * preview, through the gate with the default address policy: a search result is never owner-typed (SPEC §3).
 * A show already in the library opens its own page instead, without fetching.
 */
class ShowPreviewViewModel(private val deps: BrowseDeps, private val feedUrl: String) : ViewModel() {
    private val mutable = MutableStateFlow<ShowPreviewState>(ShowPreviewState.Loading)
    val state: StateFlow<ShowPreviewState> = mutable

    private val openedId = MutableStateFlow<Long?>(null)

    /** Set when the show is in the library (already, or just subscribed): the screen opens it, then calls [onOpened]. */
    val opened: StateFlow<Long?> = openedId

    val online: StateFlow<Boolean> = deps.online

    private var fetched: FeedPreview? = null
    private var work: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        work?.cancel()
        mutable.value = ShowPreviewState.Loading
        work = viewModelScope.launch {
            val existing = deps.repositories()?.feeds?.feeds()?.first()?.firstOrNull { it.url == feedUrl }
            if (existing != null) {
                openedId.value = existing.id
                return@launch
            }
            if (!deps.online.value) {
                mutable.value = ShowPreviewState.Failed(DiscoverProblem.Offline)
                return@launch
            }
            mutable.value = when (val outcome = deps.previewer.preview(feedUrl, ownerTyped = false)) {
                is PreviewOutcome.Failed -> ShowPreviewState.Failed(outcome.problem)
                is PreviewOutcome.Ready -> {
                    fetched = outcome.preview
                    ready(outcome.preview)
                }
            }
        }
    }

    private suspend fun ready(preview: FeedPreview): ShowPreviewState.Ready {
        val description = withContext(Dispatchers.Default) {
            (ShowNotes.sanitize(preview.feed.description) as? NotesResult.Ok)?.text?.takeIf { it.text.isNotBlank() }
        }
        return ShowPreviewState.Ready(
            title = preview.title,
            author = preview.author,
            description = description,
            episodes = preview.feed.episodes.filter { it.enclosure != null }
                .map { PreviewEpisode(AppleSearch.clean(it.title), it.publishedAt, it.durationMs) },
            isSecure = preview.isSecure,
        )
    }

    /** Not owner-typed; the fetched episodes are applied at once so the show page has them. */
    fun subscribe() {
        val preview = fetched ?: return
        val ready = mutable.value as? ShowPreviewState.Ready ?: return
        if (ready.subscribing) return
        mutable.value = ready.copy(subscribing = true, message = null)
        viewModelScope.launch {
            when (val outcome = deps.subscriptions.subscribe(preview.url, ownerTyped = false, preview = preview.feed)) {
                is SubscribeOutcome.Subscribed -> openedId.value = outcome.feedId
                is SubscribeOutcome.AlreadySubscribed -> openedId.value = outcome.feedId
                is SubscribeOutcome.Refused -> fail(DiscoverProblem.BadUrl(outcome.problem))
                SubscribeOutcome.LibraryClosed -> fail(DiscoverProblem.LibraryClosed)
            }
        }
    }

    private fun fail(problem: DiscoverProblem) =
        mutable.update { (it as? ShowPreviewState.Ready)?.copy(subscribing = false, message = problem) ?: it }

    fun dismissMessage() = mutable.update { (it as? ShowPreviewState.Ready)?.copy(message = null) ?: it }

    fun onOpened() {
        openedId.value = null
    }
}
