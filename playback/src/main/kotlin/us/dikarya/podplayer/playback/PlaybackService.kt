/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import android.app.PendingIntent
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.util.Clock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import us.dikarya.podplayer.core.imagesandbox.ArtworkBitmaps

/** What the app hands the playback service. The Application implements [PlaybackHost]. */
class PlaybackDependencies(
    val store: PlaybackStore,
    /** The gate's call factory for a feed's owner-approved host (null: no exception). */
    val callFactoryFor: (approvedHost: String?) -> Call.Factory,
    /** Sandbox-produced artwork; the session's bitmap loader reads only these (SPEC §6). */
    val artwork: ArtworkBitmaps,
    val playGate: PlayGate,
    /** Lives as long as the process, so the last position write survives the service. */
    val processScope: CoroutineScope,
    /** Opens the app when the notification is tapped. */
    val sessionActivity: PendingIntent?,
)

interface PlaybackHost {
    fun playbackDependencies(): PlaybackDependencies
}

/**
 * The playback service (SPEC §10): one ExoPlayer behind a media library session. The session is the single
 * source of truth for playback state; our screens, the notification, the lock screen, Bluetooth and (from S10)
 * Android Auto only observe it and send commands.
 */
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: PlaybackEngine? = null
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val deps = (application as PlaybackHost).playbackDependencies()
        val player = PlayerFactory.create(this, GatedMediaSourceFactory(deps.callFactoryFor))
        val engine = PlaybackEngine(player, deps.store, scope, Clock.DEFAULT, deps.processScope)
        engine.playGate = deps.playGate
        engine.onStreamReconnecting = {
            Toast.makeText(this, R.string.playback_stream_reconnecting, Toast.LENGTH_SHORT).show()
        }
        engine.onPlaybackRefused = {
            Toast.makeText(this, R.string.playback_stream_refused, Toast.LENGTH_LONG).show()
        }
        val session = MediaLibrarySession.Builder(this, engine.sessionPlayer, SessionCallback(engine, scope))
            .setBitmapLoader(ArtworkBitmapLoader(deps.artwork, deps.processScope))
            .setMediaButtonPreferences(SessionCallback.buttons(this, engine.settings))
            .apply { deps.sessionActivity?.let(::setSessionActivity) }
            .build()
        engine.currentController = { session.controllerForCurrentRequest }
        engine.start()
        scope.launch { deps.store.settings().collect { session.setMediaButtonPreferences(SessionCallback.buttons(this@PlaybackService, it)) } }
        scope.launch { engine.sleepTimer.collect { session.setSessionExtras(sleepExtras(it)) } }
        this.engine = engine
        this.session = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        engine?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SLEEP_MODE = "sleep_mode"
        const val EXTRA_SLEEP_REMAINING = "sleep_remaining_ms"
        const val SLEEP_OFF = 0
        const val SLEEP_MINUTES = 1
        const val SLEEP_END_OF_EPISODE = 2

        /** The sleep timer, for our screens, as session extras. */
        fun sleepExtras(state: SleepTimerState): Bundle = Bundle().apply {
            when (state) {
                SleepTimerState.Off -> putInt(EXTRA_SLEEP_MODE, SLEEP_OFF)
                SleepTimerState.EndOfEpisode -> putInt(EXTRA_SLEEP_MODE, SLEEP_END_OF_EPISODE)
                is SleepTimerState.Minutes -> {
                    putInt(EXTRA_SLEEP_MODE, SLEEP_MINUTES)
                    putLong(EXTRA_SLEEP_REMAINING, state.remainingMs)
                }
            }
        }
    }
}
