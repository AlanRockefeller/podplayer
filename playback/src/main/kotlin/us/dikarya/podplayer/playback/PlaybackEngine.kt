/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PlaybackRecord
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.core.model.PositionCause
import us.dikarya.podplayer.core.model.PositionChange

/** The service-owned sleep timer (SPEC §10). */
sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /**
     * Pauses after [remainingMs] more of listening, fading out over the last 10 seconds. It counts only while audio
     * plays: a pause (a phone call) doesn't use it up. Updated once a second.
     */
    data class Minutes(val remainingMs: Long) : SleepTimerState

    data object EndOfEpisode : SleepTimerState
}

/** LESSONS "Position jumps": a move larger than this, with no event explaining it, is logged. */
internal object JumpDetector {
    const val TOLERANCE_MS = 2_000L

    fun isUnexplained(fromMs: Long, elapsedMs: Long, toMs: Long, speed: Float): Boolean =
        abs(toMs - (fromMs + (elapsedMs * speed).toLong())) > TOLERANCE_MS
}

/**
 * Playback rules on top of one ExoPlayer (SPEC §10, CLAUDE.md "Playback"). The player is the source of truth
 * for playback state; the database queue is the source of truth for Up Next, and the player only ever holds the
 * current episode at index 0 plus the next one.
 *
 * Everything here runs on the player's application thread (the main thread). [scope] runs on it too.
 * [persistScope] outlives the service, so the last position write on release isn't cancelled with it.
 */
class PlaybackEngine(
    private val player: ExoPlayer,
    private val store: PlaybackStore,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val persistScope: CoroutineScope = scope,
) {
    /** The player the session exposes: commands from controllers go through here. */
    val sessionPlayer: PodPlayer = PodPlayer(player, this)

    var playGate: PlayGate = PlayGate { null }

    /** The controller whose request is being handled (the session's), or null for our own calls. */
    var currentController: () -> MediaSession.ControllerInfo? = { null }

    /**
     * Called on the main thread when a stream that dropped (or was cut short) starts loading again, at most once
     * per [RECONNECT_NOTICE_GAP_MS]. The service shows a brief notice.
     */
    var onStreamReconnecting: () -> Unit = {}

    /**
     * Called on the main thread when our URL or address policy refused the episode's stream, with the reason in
     * plain words (a host at most, never the URL). The service shows a brief notice.
     */
    var onPlaybackRefused: (String) -> Unit = {}

    private var lastReconnectNoticeAt: Long? = null

    private val loads = object : AnalyticsListener {
        override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, retryCount: Int) {
            if (retryCount < 1) return
            val scheme = loadEventInfo.dataSpec.uri.scheme
            if (scheme != "http" && scheme != "https") return
            val now = clock.elapsedRealtime()
            lastReconnectNoticeAt?.let { if (now - it < RECONNECT_NOTICE_GAP_MS) return }
            lastReconnectNoticeAt = now
            // Never the URL: it may carry a private token (CLAUDE.md).
            store.log("Episode ${currentId ?: "?"}: the stream dropped; reconnecting (attempt $retryCount)")
            onStreamReconnecting()
        }
    }

    private val sleep = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimer: StateFlow<SleepTimerState> = sleep

    internal var settings = PlaybackSettings()
        private set

    private val handler = clock.createHandler(player.applicationLooper, null)
    private val playlist = Mutex()
    private var started = false
    private var released = false
    private var ticking = false
    private var sleepRemainingMs = 0L
    private var sleepCountedAt = 0L
    private var sleepCounting = false

    private var currentId: Long? = null
    private var currentFeedId: Long? = null

    /**
     * The playlist's next episode and its saved position, read when it was put there: the move to it happens on
     * the player's thread without a chance to ask the store, and it must start where it was left.
     */
    private var nextSaved: Pair<Long, Long>? = null
    private var markedPlayed = false
    private var switchToFilePending = false
    private val brokenFiles = mutableSetOf<Long>()

    /** True while we replace the playlist ourselves, so the resulting events aren't mistaken for user actions. */
    private var rebuilding = false

    /** Set when the end-of-episode timer moved us to the next item. */
    private var advancingAfterPause = false
    private var pendingCause: PositionCause? = null
    private var lastSaveAt = 0L
    private var pausedAt: Long? = null
    private var sample: Sample? = null

    private class Sample(val positionMs: Long, val at: Long, val speed: Float)

    fun start() {
        check(!started) { "start() once" }
        started = true
        player.addListener(listener)
        player.addAnalyticsListener(loads)
        scope.launch { store.settings().collect(::onSettings) }
        scope.launch { store.downloaded().collect(::onDownloaded) }
        scope.launch {
            playlist.withLock { restore() }
            store.queue().collect { reconcile() }
        }
    }

    /** Ends the engine; the position is saved first (CLAUDE.md: on service destroy). */
    fun release() {
        if (released) return
        persist(persistScope)
        released = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.removeAnalyticsListener(loads)
        sleep.value = SleepTimerState.Off
    }

    // --- Commands ---

    /**
     * Plays [id], putting it at the top of the queue: from [startAtMs] when given (a timestamp in its show notes),
     * else from its saved position. The episode already loaded isn't reloaded, so it never jumps back to its
     * last save. Unknown ids change nothing.
     */
    fun playEpisode(id: Long, startAtMs: Long? = null) {
        scope.launch {
            playlist.withLock {
                if (store.episode(id) == null) {
                    store.log("Can't play episode $id: it isn't in the library")
                    return@withLock
                }
                // Owner, 2026-11-18: "if I listen to one podcast, then switch to another, I don't want the first one
                // to be in the queue; if I switch out, I probably lost interest". So an episode you started and leave
                // goes out of the queue; one only waiting there (loaded at 0, never listened to) stays. Its position
                // is saved by load(), so it resumes if picked up again.
                val leaving = currentId?.takeIf { it != id && player.mediaItemCount > 0 && player.currentPosition > 0 }
                if (leaving != null) store.removeFromQueue(leaving)
                store.putAtQueueHead(id)
                val start = startAtMs?.coerceAtLeast(0)
                if (id == currentId && player.mediaItemCount > 0) {
                    if (start != null) seekWithCause(clampToDuration(start), PositionCause.Seek) else {
                        restartIfEnded()
                        maybeRewindAfterPause()
                    }
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.playWhenReady = true
                } else {
                    load(id, play = true, startAtMs = start)
                }
            }
        }
    }

    private fun clampToDuration(positionMs: Long): Long {
        val duration = player.duration
        return if (duration != C.TIME_UNSET && duration > 0) positionMs.coerceAtMost(duration) else positionMs
    }

    fun skipBack() {
        if (player.mediaItemCount == 0) return
        seekWithCause((player.currentPosition - settings.skipBackSeconds * 1000L).coerceAtLeast(0), PositionCause.SkipBack)
    }

    fun skipForward() {
        if (player.mediaItemCount == 0) return
        val target = player.currentPosition + settings.skipForwardSeconds * 1000L
        val duration = player.duration
        seekWithCause(if (duration != C.TIME_UNSET && duration > 0) target.coerceAtMost(duration) else target, PositionCause.SkipForward)
    }

    /** [speed] null clears the show's own speed. [forShow] false sets the global default. */
    fun setSpeed(speed: Float?, forShow: Boolean) {
        scope.launch {
            val feed = currentFeedId
            if (forShow) {
                if (feed != null) store.setShowSpeed(feed, speed?.let(PlaybackSettings::clampSpeed))
            } else {
                store.setGlobalSpeed(speed ?: 1.0f)
            }
            applySpeed()
        }
    }

    fun sleepAfterMinutes(minutes: Int) {
        cancelSleepTimer()
        sleepRemainingMs = minutes.coerceIn(MIN_SLEEP_MINUTES, MAX_SLEEP_MINUTES) * 60_000L
        sleepCountedAt = clock.elapsedRealtime()
        sleepCounting = player.isPlaying
        sleep.value = SleepTimerState.Minutes(sleepRemainingMs)
    }

    fun sleepAtEndOfEpisode() {
        cancelSleepTimer()
        sleep.value = SleepTimerState.EndOfEpisode
        player.pauseAtEndOfMediaItems = true
    }

    fun cancelSleepTimer() {
        sleep.value = SleepTimerState.Off
        player.pauseAtEndOfMediaItems = false
        player.volume = 1.0f
    }

    /** For playback resumption after the service or process died: the current episode, else the queue head. */
    suspend fun resumptionItems(): MediaSession.MediaItemsWithStartPosition? {
        val queue = store.queue().first()
        val id = store.currentEpisodeId() ?: queue.firstOrNull() ?: return null
        val episode = store.episode(id) ?: return null
        val next = queue.firstOrNull { it != id }?.let { store.episode(it) }
        return MediaSession.MediaItemsWithStartPosition(
            listOfNotNull(itemFor(episode), next?.let(::itemFor)),
            0,
            store.startPosition(id),
        )
    }

    /**
     * A controller chose episode [id] (a car's or Android Auto's list): that episode from its saved position,
     * then the queue's next. Null for an unknown id.
     */
    suspend fun itemsStartingWith(id: Long?): MediaSession.MediaItemsWithStartPosition? {
        val episode = id?.let { store.episode(it) } ?: return null
        val next = store.queue().first().firstOrNull { it != id }?.let { store.episode(it) }
        val start = store.startPosition(episode.id)
        if (start > 0) logPosition(episode.id, 0, start, PositionCause.Restore)
        return MediaSession.MediaItemsWithStartPosition(listOfNotNull(itemFor(episode), next?.let(::itemFor)), 0, start)
    }

    /** Items for ids a controller asked for, built from the library. URIs a controller sends are never used. */
    suspend fun itemsFor(ids: List<String>): List<MediaItem> =
        ids.mapNotNull { it.toLongOrNull() }.mapNotNull { store.episode(it) }.map(::itemFor)

    // --- Called by PodPlayer for controller requests ---

    internal fun requestPlay() {
        val controller = currentController()
        playGate.ignoreReason(controller)?.let { reason ->
            store.log("Ignored Play from ${controller?.packageName}: $reason")
            return
        }
        if (player.mediaItemCount == 0) {
            scope.launch {
                playlist.withLock {
                    val id = store.currentEpisodeId() ?: store.queue().first().firstOrNull() ?: return@withLock
                    load(id, play = true)
                }
            }
            return
        }
        restartIfEnded()
        maybeRewindAfterPause()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
    }

    internal fun requestSeek(positionMs: Long) {
        if (player.mediaItemCount == 0) return
        seekWithCause(positionMs.coerceAtLeast(0), PositionCause.Seek)
    }

    // --- Loading and the queue projection ---

    private suspend fun restore() {
        if (player.mediaItemCount > 0) return
        val id = store.currentEpisodeId() ?: store.queue().first().firstOrNull() ?: return
        load(id, play = false)
    }

    /**
     * Replaces the playlist with [id] and the next queued episode, at [startAtMs] or else the saved position. Call
     * with [playlist] held.
     */
    private suspend fun load(id: Long, play: Boolean, startAtMs: Long? = null): Boolean {
        val episode = store.episode(id) ?: return false
        val saved = store.startPosition(id)
        val known = episode.durationMs?.takeIf { it > 0 }
        val start = startAtMs?.let { if (known != null) it.coerceAtMost(known) else it } ?: saved
        val next = store.queue().first().firstOrNull { it != id }?.let { store.episode(it) }
        nextSaved = next?.let { it.id to store.startPosition(it.id) }
        if (released) return false
        if (currentId != null && currentId != id) persist()
        adopt(episode.id, episode.feedId)
        store.setCurrentEpisodeId(id)
        rebuilding = true
        try {
            player.setMediaItems(listOfNotNull(itemFor(episode), next?.let(::itemFor)), 0, start)
            if (play) {
                player.prepare()
                player.playWhenReady = true
            }
        } finally {
            rebuilding = false
        }
        lastSaveAt = clock.elapsedRealtime()
        resample()
        if (startAtMs != null) {
            logPosition(id, saved, start, PositionCause.Seek)
            persistScope.launch { store.savePosition(id, start, null) }
        } else if (start > 0) {
            logPosition(id, 0, start, PositionCause.Restore)
        }
        applySpeed()
        updateWakeMode()
        fetchMissingArtwork(episode)
        return true
    }

    /**
     * The lock screen and notification show artwork from sandbox cache files only (SPEC §6). An episode whose
     * image was never shown in the app has none cached, so fetch it now and add it to the playing item's
     * metadata. The source accepts the new item in place, so the audio isn't touched.
     */
    private fun fetchMissingArtwork(episode: PlayableEpisode) {
        if (episode.artworkUri != null) return
        val id = episode.id
        scope.launch {
            val uri = store.fetchArtwork(id) ?: return@launch
            playlist.withLock {
                if (released || currentId != id || player.mediaItemCount == 0) return@withLock
                val item = player.getMediaItemAt(0)
                if (item.mediaId != id.toString() || item.mediaMetadata.artworkUri != null) return@withLock
                val updated = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(uri).build()).build()
                rebuilding = true
                try {
                    player.replaceMediaItem(0, updated)
                } finally {
                    rebuilding = false
                }
            }
        }
    }

    /** Keeps index 1 equal to the first queued episode that isn't playing, from its file when it has one. */
    private suspend fun reconcile() {
        playlist.withLock {
            if (released) return
            val current = currentId
            if (current == null) {
                // Nothing loaded and something queued: load the queue head, paused, so it shows as up next.
                val head = store.queue().first().firstOrNull()
                if (player.mediaItemCount == 0 && head != null) load(head, play = false)
                return
            }
            if (store.episode(current) == null) {
                // The loaded episode left the library (its show was unsubscribed, 2026-11-18): it leaves the player,
                // and the queue's head loads paused, as after a restart.
                rebuilding = true
                try {
                    player.playWhenReady = false
                    player.clearMediaItems()
                } finally {
                    rebuilding = false
                }
                currentId = null
                currentFeedId = null
                nextSaved = null
                store.setCurrentEpisodeId(null)
                store.queue().first().firstOrNull()?.let { load(it, play = false) }
                return
            }
            val wantedId = store.queue().first().firstOrNull { it != current }
            nextSaved = wantedId?.let { it to store.startPosition(it) }
            val wanted = wantedId?.let { store.episode(it) }?.let(::itemFor)
            if (currentId != current || player.mediaItemCount == 0) return
            val existing = if (player.mediaItemCount > 1) player.getMediaItemAt(1) else null
            if (sameSource(existing, wanted) && player.mediaItemCount <= 2) return
            rebuilding = true
            try {
                if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
                if (wanted != null) player.addMediaItem(wanted)
            } finally {
                rebuilding = false
            }
        }
    }

    private fun sameSource(a: MediaItem?, b: MediaItem?): Boolean =
        a?.mediaId == b?.mediaId && ItemSource.of(a)?.streaming == ItemSource.of(b)?.streaming

    private fun itemFor(episode: PlayableEpisode): MediaItem =
        episode.toMediaItem(useFile = episode.localFile != null && episode.id !in brokenFiles)

    private fun adopt(id: Long, feedId: Long?) {
        if (currentId != id) {
            markedPlayed = false
            pausedAt = null
        }
        currentId = id
        currentFeedId = feedId
        switchToFilePending = false
    }

    private val streaming: Boolean get() = ItemSource.of(player.currentMediaItem)?.streaming ?: false

    private fun updateWakeMode() {
        // Streams need Wi-Fi kept awake; files only the CPU (SPEC §10: network wake mode while streaming).
        player.setWakeMode(if (streaming) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
    }

    private fun onDownloaded(ids: Set<Long>) {
        val current = currentId
        if (current != null && current in ids && streaming && current !in brokenFiles) {
            // Never switch mid-play: that would be an audible glitch (owner, M1-S7). Paused: switch now.
            if (player.isPlaying) switchToFilePending = true else scope.launch { switchToFile() }
        }
        scope.launch { reconcile() }
    }

    /** Moves the playing episode from its stream to its finished download, at the same position. */
    private suspend fun switchToFile() {
        playlist.withLock {
            switchToFilePending = false
            val id = currentId ?: return
            if (!streaming) return
            val episode = store.episode(id)?.takeIf { it.localFile != null } ?: return
            replaceCurrent(itemFor(episode), PositionCause.SwitchToFile)
        }
    }

    private fun replaceCurrent(item: MediaItem, cause: PositionCause) {
        val position = player.currentPosition
        val next = (1 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        rebuilding = true
        try {
            player.setMediaItems(listOf(item) + next, 0, position)
            player.prepare()
        } finally {
            rebuilding = false
        }
        logPosition(currentId ?: return, position, position, cause)
        updateWakeMode()
    }

    // --- Episode boundaries ---

    /** [previous] ended (or reached the end-of-episode timer): played, out of the queue. */
    private fun finished(previous: Long?) {
        if (previous == null) return
        scope.launch {
            store.markPlayed(previous)
            store.appendAction(previous, EpisodeActionKind.Played, null, null)
            store.removeFromQueue(previous)
        }
    }

    private fun onTransition(item: MediaItem?, reason: Int) {
        if (rebuilding) return
        val newId = item?.mediaId?.toLongOrNull() ?: return
        val previous = currentId
        if (newId == previous) return
        val previousPosition = sample?.positionMs ?: 0
        val endedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || advancingAfterPause
        advancingAfterPause = false
        // Keep the playing item at index 0.
        val index = player.currentMediaItemIndex
        val saved = nextSaved?.takeIf { it.first == newId }?.second ?: 0
        nextSaved = null
        rebuilding = true
        try {
            if (index > 0) player.removeMediaItems(0, index)
            // The next episode starts at its saved position, like every other way into an episode (owner's phone,
            // 2026-11-18: one saved at 34 s started over). Logged once, below, as the move to the next episode.
            if (endedNaturally && saved > 0 && player.currentPosition < saved) player.seekTo(saved)
        } finally {
            rebuilding = false
        }
        adopt(newId, null)
        val chosenByController = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        if (endedNaturally) {
            finished(previous)
            logPosition(newId, previousPosition, player.currentPosition, PositionCause.NextEpisode)
        } else if (previous != null && !chosenByController) {
            // A controller skipped to the next item: the episode it left stays unplayed, out of the queue.
            scope.launch { store.removeFromQueue(previous) }
        }
        scope.launch {
            store.setCurrentEpisodeId(newId)
            // An episode a controller chose goes to the top of the queue, like one chosen in the app.
            if (chosenByController) store.putAtQueueHead(newId)
            val episode = store.episode(newId)
            currentFeedId = episode?.feedId
            applySpeed()
            // The next item was built with whatever artwork was cached then; fetch it if there was none.
            if (episode != null && player.currentMediaItem?.mediaMetadata?.artworkUri == null) {
                fetchMissingArtwork(episode.copy(artworkUri = null))
            }
        }
        lastSaveAt = clock.elapsedRealtime()
        updateWakeMode()
        resample()
    }

    /**
     * Nothing follows the episode that just ended: it is played and out of the queue, and stays loaded, paused at its
     * end, so the player still shows it (owner, 2026-11-16). Play starts it over ([restartIfEnded]).
     */
    private fun endOfLastEpisode() {
        val previous = currentId ?: return
        player.playWhenReady = false
        if (sleep.value == SleepTimerState.EndOfEpisode) cancelSleepTimer()
        finished(previous)
    }

    /** Play on an episode that has ended starts it from the beginning. */
    private fun restartIfEnded() {
        if (player.playbackState == Player.STATE_ENDED) seekWithCause(0, PositionCause.Seek)
    }

    private fun onPausedAtEndOfItem() {
        // The end-of-episode sleep timer: stop here, with the next episode loaded at its saved position.
        cancelSleepTimer()
        if (player.mediaItemCount > 1) {
            advancingAfterPause = true
            val nextId = player.getMediaItemAt(1).mediaId.toLongOrNull()
            player.seekTo(1, nextSaved?.takeIf { it.first == nextId }?.second ?: 0)
        } else {
            endOfLastEpisode()
        }
    }

    // --- Positions ---

    private fun persist(on: CoroutineScope = scope) {
        val id = currentId ?: return
        if (player.mediaItemCount == 0) return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        lastSaveAt = clock.elapsedRealtime()
        on.launch { store.savePosition(id, position, duration) }
        checkNearlyFinished(id, position, duration)
    }

    /** SPEC §10: 98% of an episode counts as played, while it stays loaded until it ends or is left. */
    private fun checkNearlyFinished(id: Long, position: Long, duration: Long?) {
        if (markedPlayed || duration == null) return
        if (PlaybackRecord.countsAsPlayed(position, duration)) {
            markedPlayed = true
            scope.launch {
                store.markPlayed(id)
                store.appendAction(id, EpisodeActionKind.Played, position, duration)
            }
        }
    }

    private fun seekWithCause(positionMs: Long, cause: PositionCause) {
        pendingCause = cause
        player.seekTo(positionMs)
    }

    private fun maybeRewindAfterPause() {
        val since = pausedAt ?: return
        pausedAt = null
        if (!shouldRewindAfterPause(settings, pausedForMs = clock.elapsedRealtime() - since)) return
        seekWithCause((player.currentPosition - settings.rewindSeconds * 1000L).coerceAtLeast(0), PositionCause.RewindAfterPause)
    }

    private fun logPosition(id: Long, from: Long, to: Long, cause: PositionCause) {
        val change = PositionChange(episodeId = id, at = clock.currentTimeMillis(), fromMs = from, toMs = to, cause = cause, streaming = streaming)
        persistScope.launch { store.logPosition(change) }
    }

    private fun resample() {
        sample = Sample(player.currentPosition, clock.elapsedRealtime(), player.playbackParameters.speed)
    }

    // --- Speed ---

    private fun onSettings(new: PlaybackSettings) {
        settings = new
        scope.launch { applySpeed() }
    }

    private suspend fun applySpeed() {
        val feed = currentFeedId
        val speed = PlaybackSettings.clampSpeed((feed?.let { store.showSpeed(it) }) ?: settings.speed)
        if (player.playbackParameters.speed != speed) {
            // Pitch stays 1.0 (SPEC §10: pitch preserved).
            player.playbackParameters = PlaybackParameters(speed)
            resample()
        }
    }

    // --- The tick: periodic saves, 98%, jump detection, sleep timer ---

    /** Ticks only while audio plays, so a paused player never wakes the CPU (the sleep timer is paused too). */
    private fun ensureTicking() {
        if (ticking || released) return
        if (!player.isPlaying) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            if (released) return
            val now = clock.elapsedRealtime()
            if (player.isPlaying) {
                val previous = sample
                if (previous != null && JumpDetector.isUnexplained(previous.positionMs, now - previous.at, player.currentPosition, previous.speed)) {
                    currentId?.let { logPosition(it, previous.positionMs, player.currentPosition, PositionCause.Unexplained) }
                }
                resample()
                if (now - lastSaveAt >= SAVE_EVERY_MS) {
                    persist()
                } else {
                    currentId?.let { checkNearlyFinished(it, player.currentPosition, player.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 }) }
                }
            }
            stepSleepTimer(now)
            ensureTicking()
        }
    }

    /**
     * Counts listening time only. [sleepCounting] follows the playing events rather than `player.isPlaying`, so a
     * tick that runs between a resume and its event never counts the paused gap as listening.
     */
    private fun stepSleepTimer(now: Long) {
        if (sleep.value !is SleepTimerState.Minutes) return
        val listened = if (sleepCounting) now - sleepCountedAt else 0
        sleepCountedAt = now
        val before = sleepRemainingMs
        sleepRemainingMs -= listened.coerceAtLeast(0)
        when {
            sleepRemainingMs <= 0 -> {
                sleep.value = SleepTimerState.Off
                player.playWhenReady = false
                player.volume = 1.0f
            }
            else -> {
                if (sleepRemainingMs <= FADE_MS && player.isPlaying) player.volume = sleepRemainingMs.toFloat() / FADE_MS
                // Publish once a second; the session sends it to our screens as extras.
                if (before / 1000 != sleepRemainingMs / 1000) sleep.value = SleepTimerState.Minutes(sleepRemainingMs)
            }
        }
    }

    // --- Player events ---

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (rebuilding) return
            val id = currentId
            if (!playWhenReady) {
                persist()
                pausedAt = clock.elapsedRealtime()
                if (id != null) scope.launch { store.appendAction(id, EpisodeActionKind.Pause, player.currentPosition, null) }
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    onPausedAtEndOfItem()
                } else if (switchToFilePending) {
                    scope.launch { switchToFile() }
                }
            } else if (id != null) {
                scope.launch { store.appendAction(id, EpisodeActionKind.Play, player.currentPosition, null) }
            }
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            // A transient focus loss (a call, a navigation prompt) keeps playWhenReady but stops the sound.
            if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) persist()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (rebuilding) return
            when (state) {
                Player.STATE_IDLE -> persist()
                Player.STATE_ENDED -> endOfLastEpisode()
                Player.STATE_BUFFERING, Player.STATE_READY -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            resample()
            // The sleep timer counts listening time: up to a stop, and again from a start.
            val now = clock.elapsedRealtime()
            if (isPlaying) {
                sleepCountedAt = now
                sleepCounting = true
            } else {
                stepSleepTimer(now)
                sleepCounting = false
            }
            ensureTicking()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onTransition(mediaItem, reason)

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val id = currentId
            if (!rebuilding && id != null && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                when (reason) {
                    Player.DISCONTINUITY_REASON_SEEK -> {
                        logPosition(id, oldPosition.positionMs, newPosition.positionMs, pendingCause ?: PositionCause.Seek)
                        persist()
                        if (switchToFilePending) scope.launch { switchToFile() }
                    }
                    // Transitions are logged by onTransition; removals are our own playlist edits.
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION, Player.DISCONTINUITY_REASON_REMOVE -> Unit
                    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
                    Player.DISCONTINUITY_REASON_INTERNAL,
                    Player.DISCONTINUITY_REASON_SKIP,
                    Player.DISCONTINUITY_REASON_SILENCE_SKIP,
                    ->
                        logPosition(id, oldPosition.positionMs, newPosition.positionMs, PositionCause.Player)
                }
            }
            pendingCause = null
            resample()
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = currentId ?: return
            if (!streaming) {
                // The downloaded file failed: play the stream from the same position instead.
                brokenFiles += id
                store.log("Episode $id: the downloaded file failed (${error.errorCodeName}); playing the stream instead")
                scope.launch {
                    playlist.withLock {
                        val episode = store.episode(id) ?: return@withLock
                        replaceCurrent(itemFor(episode), PositionCause.FallBackToStream)
                    }
                }
            } else {
                persist()
                val refused = refusalReason(error)
                if (refused != null) {
                    // Never the URL (CLAUDE.md): the reason names a host at most.
                    store.log("Episode $id: the stream was refused: $refused")
                    onPlaybackRefused(refused)
                } else {
                    store.log("Episode $id: playback failed (${error.errorCodeName})")
                }
            }
        }
    }

    companion object {
        /** SPEC §10: "rewind N seconds after a pause longer than M minutes", only when turned on. */
        fun shouldRewindAfterPause(settings: PlaybackSettings, pausedForMs: Long): Boolean =
            settings.rewindAfterPause && pausedForMs >= settings.rewindAfterPauseMinutes * 60_000L

        const val TICK_MS = 250L
        const val SAVE_EVERY_MS = 10_000L
        const val FADE_MS = 10_000L
        const val MIN_SLEEP_MINUTES = 5
        const val MAX_SLEEP_MINUTES = 120

        /** A stream dropping again and again shows one reconnect notice per this long, not one per attempt. */
        const val RECONNECT_NOTICE_GAP_MS = 30_000L
    }
}
