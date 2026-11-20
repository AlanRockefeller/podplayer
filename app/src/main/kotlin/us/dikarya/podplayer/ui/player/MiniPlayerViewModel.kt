/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import us.dikarya.podplayer.ui.browse.BrowseDeps

/** What the mini-player shows. Everything but the artwork comes from the session. */
data class MiniPlayerUi(
    val episodeId: Long,
    val title: String?,
    val showTitle: String?,
    val playing: Boolean,
    val buffering: Boolean,
    /** 0..1, or null while the length is unknown. */
    val progress: Float?,
    val imageUrl: String? = null,
    val approvedHost: String? = null,
)

/** The mini-player above the navigation (SPEC §12): shown while the session has an episode loaded. */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniPlayerViewModel(private val deps: BrowseDeps) : ViewModel() {
    private class Art(val imageUrl: String?, val approvedHost: String?)

    private val art = deps.playback.state.map { it.episodeId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) {
            flowOf(Art(null, null))
        } else {
            flow {
                val repos = deps.repositories()
                val episode = repos?.episodes?.get(id)
                val feed = episode?.let { repos.feeds.get(it.feedId) }
                emit(Art(episode?.imageUrl ?: feed?.imageUrl, feed?.ownerApprovedHost))
            }
        }
    }

    val state: StateFlow<MiniPlayerUi?> = combine(deps.playback.state, art) { playback, art ->
        val id = playback.episodeId ?: return@combine null
        val duration = playback.durationMs
        MiniPlayerUi(
            episodeId = id,
            title = playback.title,
            showTitle = playback.showTitle,
            playing = playback.playWhenReady,
            buffering = playback.buffering,
            progress = if (duration != null && duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else null,
            imageUrl = art.imageUrl,
            approvedHost = art.approvedHost,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /** Connects to the session if needed (the screens' side of it lives as long as the app). */
    fun connect() = deps.playback.connect()

    fun togglePlay() {
        if (deps.playback.state.value.playWhenReady) deps.playback.pause() else deps.playback.play()
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
