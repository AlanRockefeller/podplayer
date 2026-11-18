/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource

/** The one ExoPlayer configuration (SPEC §10). Tests build their player here too, with fakes injected. */
object PlayerFactory {
    /** Speech content: on focus loss the player pauses instead of ducking (LESSONS "Audio focus"). */
    val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Read-ahead for streams (owner, M1-S7): keep 10 to 15 minutes ahead and 5 minutes behind, so a few minutes
     * without signal while driving don't stop playback and skipping back doesn't re-download. The byte cap wins
     * over the durations so a high-bitrate stream can't take unbounded memory. Downloads read from disk and
     * need little.
     */
    object Buffers {
        const val STREAM_MIN_MS = 10 * 60_000
        const val STREAM_MAX_MS = 15 * 60_000
        const val BACK_BUFFER_MS = 5 * 60_000
        const val TARGET_BYTES = 96 * 1024 * 1024
        const val LOCAL_MIN_MS = 30_000
        const val LOCAL_MAX_MS = 60_000
        const val START_MS = 2_500
        const val RESTART_AFTER_REBUFFER_MS = 5_000
    }

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(Buffers.STREAM_MIN_MS, Buffers.STREAM_MAX_MS, Buffers.START_MS, Buffers.RESTART_AFTER_REBUFFER_MS)
        .setBufferDurationsMsForLocalPlayback(Buffers.LOCAL_MIN_MS, Buffers.LOCAL_MAX_MS, Buffers.START_MS, Buffers.START_MS)
        .setBackBuffer(Buffers.BACK_BUFFER_MS, true)
        .setTargetBufferBytes(Buffers.TARGET_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    /**
     * [renderers], [clock] and [forTests] are for tests; production uses the defaults. The player pauses when audio
     * becomes noisy (CLAUDE.md) and handles audio focus itself.
     */
    @SuppressLint("VisibleForTests") // setClock: production always passes Clock.DEFAULT.
    fun create(
        context: Context,
        sources: MediaSource.Factory,
        clock: Clock = Clock.DEFAULT,
        renderers: RenderersFactory? = null,
        forTests: ExoPlayer.Builder.() -> Unit = {},
    ): ExoPlayer {
        val builder = if (renderers != null) ExoPlayer.Builder(context, renderers) else ExoPlayer.Builder(context)
        return builder
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl())
            .setAudioAttributes(AUDIO_ATTRIBUTES, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setClock(clock)
            .setName("podplayer")
            .apply(forTests)
            .build()
    }
}
