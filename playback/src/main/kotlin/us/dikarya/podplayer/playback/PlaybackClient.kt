/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What our screens show. Derived only from the session (LESSONS "Playback rewrite"). */
data class PlaybackUiState(
    val connected: Boolean = false,
    val episodeId: Long? = null,
    val title: String? = null,
    val showTitle: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val speed: Float = 1.0f,
    val sleep: SleepUi = SleepUi.Off,
)

sealed interface SleepUi {
    data object Off : SleepUi

    /** [remainingMs] of listening left; it doesn't count down while paused. */
    data class Minutes(val remainingMs: Long) : SleepUi

    data object EndOfEpisode : SleepUi
}

/**
 * Our screens' side of the playback session: connects a [MediaController] marked as our own UI, exposes the
 * session's state as a Flow, and sends commands. Main thread only.
 */
class PlaybackClient(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var connecting = false
    private val mutable = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutable.asStateFlow()

    /** Connects if needed; safe to call repeatedly. */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setConnectionHints(Bundle().apply { putBoolean(HINT_OWN_UI, true) })
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh()

                override fun onDisconnected(controller: MediaController) {
                    this@PlaybackClient.controller = null
                    mutable.value = PlaybackUiState()
                }
            })
            .buildAsync()
        future.addListener(
            {
                connecting = false
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                connected.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refresh()
                })
                controller = connected
                refresh()
                main.post(poll)
            },
            appContext.mainExecutor,
        )
    }

    fun release() {
        main.removeCallbacks(poll)
        controller?.release()
        controller = null
        mutable.value = PlaybackUiState()
    }

    fun playEpisode(id: Long) = custom(SessionCallback.Commands.PLAY_EPISODE) { putLong(SessionCallback.Commands.ARG_EPISODE_ID, id) }

    fun play() = controller?.play()

    fun pause() = controller?.pause()

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun skipBack() = controller?.seekBack()

    fun skipForward() = controller?.seekForward()

    /** [speed] null clears the show's own speed; [forShow] false sets the global default. */
    fun setSpeed(speed: Float?, forShow: Boolean) = custom(SessionCallback.Commands.SET_SPEED) {
        speed?.let { putFloat(SessionCallback.Commands.ARG_SPEED, it) }
        putBoolean(SessionCallback.Commands.ARG_FOR_SHOW, forShow)
    }

    fun sleepAfterMinutes(minutes: Int) = custom(SessionCallback.Commands.SLEEP_MINUTES) { putInt(SessionCallback.Commands.ARG_MINUTES, minutes) }

    fun sleepAtEndOfEpisode() = custom(SessionCallback.Commands.SLEEP_END_OF_EPISODE) {}

    fun cancelSleepTimer() = custom(SessionCallback.Commands.SLEEP_CANCEL) {}

    private fun custom(action: String, args: Bundle.() -> Unit) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle().apply(args))
    }

    /** The position moves without events, so poll it while connected. */
    private val poll = object : Runnable {
        override fun run() {
            if (controller == null) return
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val extras = c.sessionExtras
        mutable.value = PlaybackUiState(
            connected = true,
            episodeId = item?.mediaId?.toLongOrNull(),
            title = item?.mediaMetadata?.title?.toString(),
            showTitle = item?.mediaMetadata?.artist?.toString(),
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 },
            speed = c.playbackParameters.speed,
            sleep = when (extras.getInt(PlaybackService.EXTRA_SLEEP_MODE, PlaybackService.SLEEP_OFF)) {
                PlaybackService.SLEEP_MINUTES -> SleepUi.Minutes(extras.getLong(PlaybackService.EXTRA_SLEEP_REMAINING))
                PlaybackService.SLEEP_END_OF_EPISODE -> SleepUi.EndOfEpisode
                else -> SleepUi.Off
            },
        )
    }

    companion object {
        /** Connection hint our own screens send, so the session can tell them from other controllers. */
        const val HINT_OWN_UI = "us.dikarya.podplayer.OWN_UI"
        private const val POLL_MS = 500L
    }
}
