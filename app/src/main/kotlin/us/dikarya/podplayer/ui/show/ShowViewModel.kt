/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.feed.notes.NotesResult
import us.dikarya.podplayer.core.feed.notes.RichText
import us.dikarya.podplayer.core.feed.notes.ShowNotes
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.model.PlaybackRecord
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.playback.PlaybackUiState
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

data class ShowUiState(
    val loading: Boolean = true,
    /** Unsubscribed, or never existed: the screen goes back. */
    val gone: Boolean = false,
    val feed: Feed? = null,
    /** The show's description, sanitized (SPEC §5); null while it is prepared or when there is none. */
    val description: RichText? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val newestFirst: Boolean = true,
    /** Only the starred episodes are listed (SPEC §12). */
    val starredOnly: Boolean = false,
    /** One of this show's episodes is the session's, whether or not the filter lists it. */
    val loadedHere: Boolean = false,
    /** The show's own speed, or null for the global one. */
    val showSpeed: Float? = null,
    val globalSpeed: Float = 1.0f,
    /** SPEC §9 "Download new episodes" for this show. */
    val autoDownload: Boolean = false,
    val online: Boolean = true,
)

/** A show's page (SPEC §12): its episodes, sort, per-show speed, refresh and unsubscribe. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModel(private val deps: BrowseDeps, private val feedId: Long) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)
    private val newestFirst = MutableStateFlow(true)
    private val starredOnly = MutableStateFlow(false)
    private val unsubscribed = MutableStateFlow(false)

    private class Header(val feed: Feed?, val description: RichText?, val showSpeed: Float?, val global: PlaybackSettings, val autoDownload: Boolean)

    private fun header(repos: Repositories) = combine(
        repos.feeds.feed(feedId),
        repos.feeds.feed(feedId).map { it?.description }.distinctUntilChanged()
            .mapLatest { html -> (ShowNotes.sanitize(html) as? NotesResult.Ok)?.text?.takeIf { it.text.isNotBlank() } }
            .flowOn(Dispatchers.Default),
        repos.feedSettings.speed(feedId),
        deps.settings.playback,
        repos.feedSettings.autoDownload(feedId),
    ) { feed, description, speed, global, auto -> Header(feed, description, speed, global, auto) }

    val state: StateFlow<ShowUiState> = deps.withRepositories(ShowUiState(loading = false, gone = true)) { repos ->
        val rows = combine(
            repos.episodes.forFeed(feedId),
            repos.playback.recordsForFeed(feedId),
            repos.media.observeAll(),
            repos.queue.entries(),
            deps.playback.state,
        ) { episodes, records, media, queue, playback -> Rows(episodes, records, media.associateBy { it.episodeId }, queue.mapTo(HashSet()) { it.episodeId }, playback) }
        val view = combine(newestFirst, starredOnly) { newest, onlyStarred -> newest to onlyStarred }
        combine(header(repos), rows, view, deps.online, unsubscribed) { header, rows, (newest, onlyStarred), online, gone ->
            val feed = header.feed
            val items = rows.episodes.asSequence()
                .filter { !onlyStarred || it.starred }
                .map { e -> EpisodeItem.of(e, feed, rows.records[e.id], rows.media[e.id], e.id in rows.queued, rows.playback) }
                .toList()
            ShowUiState(
                loading = false,
                gone = gone || feed == null,
                feed = feed,
                description = header.description,
                episodes = if (newest) items else items.asReversed(),
                newestFirst = newest,
                starredOnly = onlyStarred,
                loadedHere = rows.playback.episodeId != null && rows.episodes.any { it.id == rows.playback.episodeId },
                showSpeed = header.showSpeed,
                globalSpeed = header.global.speed,
                autoDownload = header.autoDownload,
                online = online,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), ShowUiState())

    private class Rows(
        val episodes: List<Episode>,
        val records: Map<Long, PlaybackRecord>,
        val media: Map<Long, MediaFile>,
        val queued: Set<Long>,
        val playback: PlaybackUiState,
    )

    fun toggleSort() = newestFirst.update { !it }

    fun toggleStarredOnly() = starredOnly.update { !it }

    /**
     * [speed] null goes back to the global speed. While one of this show's episodes is loaded, the session sets it
     * (it applies it at once and stores it); otherwise it is stored for the show's next episode.
     */
    fun setShowSpeed(speed: Float?) {
        if (state.value.loadedHere) {
            deps.playback.setSpeed(speed?.let(PlaybackSettings::clampSpeed), forShow = true)
        } else {
            viewModelScope.launch { deps.repositories()?.feedSettings?.setSpeed(feedId, speed) }
        }
    }

    /** On: episodes published from now on are downloaded after each refresh (SPEC §9); the back catalogue isn't. */
    fun setAutoDownload(on: Boolean) {
        viewModelScope.launch { deps.repositories()?.feedSettings?.setAutoDownload(feedId, on) }
    }

    fun refresh() = deps.refresh(feedId)

    /** The refresh [refresh] asked for is running (pull-to-refresh's spinner). */
    val refreshing: kotlinx.coroutines.flow.Flow<Boolean> = deps.refreshRunning(feedId)

    fun unsubscribe() {
        unsubscribed.value = true
        viewModelScope.launch { deps.downloads.unsubscribe(feedId) }
    }

    /**
     * A show kept aside from a preview (SPEC §12) becomes a subscription, keeping what was played; the page stays
     * and shows it subscribed. Never owner-typed: the address came from a search result.
     */
    fun subscribe() {
        val feed = state.value.feed?.takeIf { !it.subscribed } ?: return
        viewModelScope.launch { deps.subscriptions.subscribe(feed.url, ownerTyped = false) }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
