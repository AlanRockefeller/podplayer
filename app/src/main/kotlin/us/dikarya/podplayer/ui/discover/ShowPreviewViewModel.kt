/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import androidx.lifecycle.ViewModel
import kotlin.math.abs
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
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.identity.EpisodeMerge
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.KeepAsideOutcome
import us.dikarya.podplayer.ui.browse.SubscribeOutcome

/**
 * The stored episode for a previewed [parsed] one: same GUID, else same audio address. Episode identity is the
 * merge's (SPEC §4); this only finds the row the merge just wrote.
 */
internal fun List<Episode>.storedIdOf(parsed: ParsedEpisode): Long? {
    val url = parsed.enclosure?.url
    // A feed can reuse a GUID for different audio, which the merge keeps as separate episodes (Codex review, PR #31):
    // the GUID and the audio together pick the row, then the audio alone, then a GUID only one row has.
    firstOrNull { it.guid != null && it.guid == parsed.guid && it.enclosure.url == url }?.let { return it.id }
    if (url != null) {
        // Episodes without GUIDs can share audio and differ in title or date; the merge keeps them apart, so the
        // title, then the nearest date, picks the row (CodeRabbit review, PR #1).
        val sameAudio = filter { it.enclosure.url == url }
        if (sameAudio.isNotEmpty()) {
            // The merge's own title rule: an item it dropped as a duplicate ("episode a") finds the row it kept.
            val title = EpisodeMerge.normalizeTitle(parsed.title)
            val sameTitle = sameAudio.filter { EpisodeMerge.normalizeTitle(it.title) == title }.ifEmpty { sameAudio }
            val at = parsed.publishedAt
            return (if (at == null) sameTitle.first() else sameTitle.minBy { abs(it.publishedAt - at) }).id
        }
    }
    return parsed.guid?.let { guid -> filter { it.guid == guid }.singleOrNull()?.id }
}

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
        /** Keeping the show aside to open or play one of its episodes (SPEC §12). */
        val opening: Boolean = false,
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

    private val openedEpisodeId = MutableStateFlow<Long?>(null)

    /** Set when an episode was opened from the preview: the screen opens its page, then calls [onEpisodeOpened]. */
    val openedEpisode: StateFlow<Long?> = openedEpisodeId

    val online: StateFlow<Boolean> = deps.online

    private var fetched: FeedPreview? = null

    /** The episodes listed, in the same order as [ShowPreviewState.Ready.episodes]. */
    private var listed: List<ParsedEpisode> = emptyList()
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
        listed = preview.feed.episodes.filter { it.enclosure != null }
        return ShowPreviewState.Ready(
            title = preview.title,
            author = preview.author,
            description = description,
            episodes = listed.map { PreviewEpisode(AppleSearch.clean(it.title), it.publishedAt, it.durationMs) },
            isSecure = preview.isSecure,
        )
    }

    /** Not owner-typed; the fetched episodes are applied at once so the show page has them. */
    fun subscribe() {
        val preview = fetched ?: return
        val ready = mutable.value as? ShowPreviewState.Ready ?: return
        if (ready.subscribing || ready.opening) return
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

    /** Opens the episode at [index] of the list on its own page (notes, play, queue, download, star). */
    fun openEpisode(index: Int) = withEpisode(index) { openedEpisodeId.value = it }

    /** Plays the episode at [index] at once; the mini player appears as for any episode. */
    fun play(index: Int) = withEpisode(index) { deps.playback.playEpisode(it) }

    /**
     * Playing without subscribing (owner, 2026-11-18): the show is kept aside in the database, unsubscribed, from
     * the feed already fetched, so playback, positions and the queue work as for any episode. Nothing more is
     * fetched here; playing contacts the episode's audio host, as playing always does.
     */
    private fun withEpisode(index: Int, then: (episodeId: Long) -> Unit) {
        val preview = fetched ?: return
        val episode = listed.getOrNull(index) ?: return
        val ready = mutable.value as? ShowPreviewState.Ready ?: return
        if (ready.opening || ready.subscribing) return
        mutable.value = ready.copy(opening = true, message = null)
        viewModelScope.launch {
            val feedId = when (val outcome = deps.subscriptions.keepAside(preview.url, preview.feed)) {
                is KeepAsideOutcome.Stored -> outcome.feedId
                is KeepAsideOutcome.Refused -> return@launch fail(DiscoverProblem.BadUrl(outcome.problem))
                KeepAsideOutcome.LibraryClosed -> return@launch fail(DiscoverProblem.LibraryClosed)
            }
            val stored = deps.repositories()?.episodes?.forFeed(feedId)?.first().orEmpty()
            val id = stored.storedIdOf(episode) ?: return@launch fail(DiscoverProblem.EpisodeUnavailable)
            mutable.update { (it as? ShowPreviewState.Ready)?.copy(opening = false) ?: it }
            then(id)
        }
    }

    fun onEpisodeOpened() {
        openedEpisodeId.value = null
    }

    private fun fail(problem: DiscoverProblem) =
        mutable.update { (it as? ShowPreviewState.Ready)?.copy(subscribing = false, opening = false, message = problem) ?: it }

    fun dismissMessage() = mutable.update { (it as? ShowPreviewState.Ready)?.copy(message = null) ?: it }

    fun onOpened() {
        openedId.value = null
    }
}
