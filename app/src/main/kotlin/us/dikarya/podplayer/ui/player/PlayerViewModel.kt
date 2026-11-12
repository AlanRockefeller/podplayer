/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.playback.PlaybackUiState
import us.dikarya.podplayer.playback.SleepUi
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.EpisodeActions
import us.dikarya.podplayer.ui.browse.EpisodeItem

/** The full player (SPEC §12). Playback fields are the session's; [item] adds the library's view of the episode. */
data class PlayerUiState(
    val loading: Boolean = true,
    val episodeId: Long? = null,
    val title: String? = null,
    val showTitle: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    /** The session's length, else the download's or the feed's while the player doesn't know it yet. */
    val durationMs: Long? = null,
    val speed: Float = 1.0f,
    /** The show's own speed, or null when it uses [globalSpeed]. */
    val showSpeed: Float? = null,
    val globalSpeed: Float = 1.0f,
    val skipBackSeconds: Int = PlaybackSettings().skipBackSeconds,
    val skipForwardSeconds: Int = PlaybackSettings().skipForwardSeconds,
    val sleep: SleepUi = SleepUi.Off,
    val item: EpisodeItem? = null,
) {
    val remainingMs: Long? get() = durationMs?.let { (it - positionMs).coerceAtLeast(0) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val deps: BrowseDeps) : ViewModel() {
    val actions = EpisodeActions(deps, viewModelScope)

    private class Library(val item: EpisodeItem?, val showSpeed: Float?)

    private fun library(repos: Repositories, id: Long?): Flow<Library> {
        if (id == null) return flowOf(Library(null, null))
        return repos.episodes.observe(id).flatMapLatest { episode ->
            if (episode == null) return@flatMapLatest flowOf(Library(null, null))
            combine(
                repos.feeds.feed(episode.feedId),
                repos.playback.record(id),
                repos.media.observe(id),
                repos.queue.entries().map { q -> q.any { it.episodeId == id } }.distinctUntilChanged(),
                repos.feedSettings.speed(episode.feedId),
            ) { feed, record, media, queued, showSpeed ->
                // The playback fields are filled in below from the live session state.
                Library(EpisodeItem.of(episode, feed, record, media, queued, PlaybackUiState(episodeId = id)), showSpeed)
            }
        }
    }

    val state: StateFlow<PlayerUiState> = deps.withRepositories(PlayerUiState(loading = false)) { repos ->
        val lib = deps.playback.state.map { it.episodeId }.distinctUntilChanged().flatMapLatest { library(repos, it) }
        combine(deps.playback.state, lib, deps.settings.playback) { playback, library, settings -> build(playback, library, settings) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), PlayerUiState())

    private fun build(playback: PlaybackUiState, library: Library, settings: PlaybackSettings): PlayerUiState {
        val id = playback.episodeId
        val item = library.item?.takeIf { it.id == id }?.copy(
            loaded = true,
            playing = playback.playWhenReady,
            livePositionMs = playback.positionMs,
        )
        return PlayerUiState(
            loading = false,
            episodeId = id,
            title = playback.title ?: item?.episode?.title,
            showTitle = playback.showTitle ?: item?.showTitle,
            playing = playback.playWhenReady,
            buffering = playback.buffering,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs ?: item?.durationMs,
            speed = playback.speed,
            showSpeed = library.showSpeed.takeIf { item != null },
            globalSpeed = settings.speed,
            skipBackSeconds = settings.skipBackSeconds,
            skipForwardSeconds = settings.skipForwardSeconds,
            sleep = playback.sleep,
            item = item,
        )
    }

    fun connect() = deps.playback.connect()

    fun togglePlay() {
        if (deps.playback.state.value.playWhenReady) deps.playback.pause() else deps.playback.play()
    }

    fun skipBack() = deps.playback.skipBack()

    fun skipForward() = deps.playback.skipForward()

    /** Held to the episode: a seek bar can't send the session past either end. */
    fun seekTo(positionMs: Long) {
        val duration = state.value.durationMs
        val target = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        deps.playback.seekTo(target)
    }

    /** [forShow] false changes the default for every show; true sets this show's own speed. */
    fun setSpeed(speed: Float, forShow: Boolean) = deps.playback.setSpeed(PlaybackSettings.clampSpeed(speed), forShow)

    /** The show goes back to the default speed. */
    fun useDefaultSpeedForShow() = deps.playback.setSpeed(null, forShow = true)

    fun sleepAfter(minutes: Int) = deps.playback.sleepAfterMinutes(minutes)

    fun sleepAtEndOfEpisode() = deps.playback.sleepAtEndOfEpisode()

    fun cancelSleep() = deps.playback.cancelSleepTimer()

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
