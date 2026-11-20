/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The player the media session exposes to every controller: our screens, the notification, the lock screen,
 * Bluetooth, the car. It routes the commands that carry rules through [PlaybackEngine]:
 * - every Play goes through the Bluetooth play gate (never auto-start on connect);
 * - skip back/forward use the owner's intervals (SPEC §10), clamped to the episode;
 * - "next" and "previous" (a car's or headset's track buttons) skip within the episode instead of changing
 *   episode, which is what a podcast listener means by them;
 * - every seek is logged with its cause.
 */
class PodPlayer internal constructor(private val exo: ExoPlayer, private val engine: PlaybackEngine) : ForwardingPlayer(exo) {
    override fun play() = engine.requestPlay()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) engine.requestPlay() else exo.playWhenReady = false
    }

    override fun seekBack() = engine.skipBack()

    override fun seekForward() = engine.skipForward()

    override fun getSeekBackIncrement(): Long = engine.settings.skipBackSeconds * 1000L

    override fun getSeekForwardIncrement(): Long = engine.settings.skipForwardSeconds * 1000L

    override fun seekToNext() = engine.skipForward()

    override fun seekToPrevious() = engine.skipBack()

    override fun seekTo(positionMs: Long) = engine.requestSeek(positionMs)

    override fun getAvailableCommands(): Player.Commands {
        val commands = exo.availableCommands
        if (!commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return commands
        return commands.buildUpon()
            .addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
}
