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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

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

    override fun pause() = engine.requestPause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) engine.requestPlay() else engine.requestPause()
    }

    /**
     * Media3 answers Play on an ended episode by seeking to its start first, before [play] asks the Bluetooth gate.
     * An ignored automatic Play must leave the episode where it ended; an obeyed one starts it over itself
     * ([PlaybackEngine.requestPlay]).
     */
    override fun seekToDefaultPosition() {
        if (exo.playbackState == Player.STATE_ENDED) return
        super.seekToDefaultPosition()
    }

    override fun seekBack() = engine.skipBack()

    override fun seekForward() = engine.skipForward()

    override fun getSeekBackIncrement(): Long = engine.settings.skipBackSeconds * 1000L

    override fun getSeekForwardIncrement(): Long = engine.settings.skipForwardSeconds * 1000L

    override fun seekToNext() = engine.skipForward()

    override fun seekToPrevious() = engine.skipBack()

    override fun seekTo(positionMs: Long) = engine.requestSeek(positionMs)

    override fun getAvailableCommands(): Player.Commands = withSkips(exo.availableCommands)

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    // The session learns of command changes from the listener's event, not from getAvailableCommands, so the event
    // carries the same commands ("next" on the last episode included).
    // ForwardingPlayer finds a listener to remove by identity, so each listener keeps its one wrapper.
    private val wrappers = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) = super.addListener(wrappers.getOrPut(listener) { withOurCommands(listener) })

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrappers.remove(listener) ?: return)
    }

    private fun withSkips(commands: Player.Commands): Player.Commands {
        if (!commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return commands
        return commands.buildUpon()
            .addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
    }

    /**
     * [listener], hearing every event, with available commands as [getAvailableCommands] reports them. A proxy, not
     * Kotlin's `by`: every Player.Listener method is a Java default method, and `by` forwards none of those.
     */
    private fun withOurCommands(listener: Player.Listener): Player.Listener =
        Proxy.newProxyInstance(Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java), CommandsHandler(listener))
            as Player.Listener

    private inner class CommandsHandler(private val listener: Player.Listener) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "PodPlayer listener for $listener"
            "onAvailableCommandsChanged" -> listener.onAvailableCommandsChanged(withSkips(args!![0] as Player.Commands))
            else -> try {
                method.invoke(listener, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
