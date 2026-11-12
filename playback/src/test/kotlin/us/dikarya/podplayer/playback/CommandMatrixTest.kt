/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LESSONS "State-dependent actions": every command in every player state. Each pair gets a fresh engine, so a
 * failure names exactly which command broke in which state.
 */
@RunWith(AndroidJUnit4::class)
class CommandMatrixTest {
    private enum class State { NothingLoaded, LoadedNotStarted, Buffering, Playing, Paused, Ended, Error }

    private val commands: Map<String, (PlaybackEngine) -> Unit> = linkedMapOf(
        "play" to { it.sessionPlayer.play() },
        "pause" to { it.sessionPlayer.pause() },
        "playWhenReady" to { it.sessionPlayer.playWhenReady = true },
        "seekTo" to { it.sessionPlayer.seekTo(45_000) },
        "seekToNegative" to { it.sessionPlayer.seekTo(-5) },
        "seekBack" to { it.sessionPlayer.seekBack() },
        "seekForward" to { it.sessionPlayer.seekForward() },
        "next" to { it.sessionPlayer.seekToNext() },
        "previous" to { it.sessionPlayer.seekToPrevious() },
        "nextItem" to { it.sessionPlayer.seekToNextMediaItem() },
        "stop" to { it.sessionPlayer.stop() },
        "prepare" to { it.sessionPlayer.prepare() },
        "speed" to { it.setSpeed(1.7f, forShow = false) },
        "showSpeed" to { it.setSpeed(2.2f, forShow = true) },
        "sleepMinutes" to { it.sleepAfterMinutes(15) },
        "sleepEndOfEpisode" to { it.sleepAtEndOfEpisode() },
        "cancelSleep" to { it.cancelSleepTimer() },
        "playEpisode" to { it.playEpisode(2) },
        "playUnknownEpisode" to { it.playEpisode(404) },
    )

    private fun enter(state: State): Harness {
        val h = Harness()
        h.store.add(1, durationMs = 20_000)
        h.store.add(2)
        h.store.add(3, durationMs = 10_000)
        when (state) {
            State.NothingLoaded -> h.start()
            State.LoadedNotStarted -> {
                h.store.current = 1
                h.store.queue.value = listOf(1, 2)
                h.start()
            }
            State.Buffering -> {
                h.sources.stalled += 1L
                h.store.queue.value = listOf(1, 2)
                h.start()
                h.engine.playEpisode(1)
                idle()
            }
            State.Playing, State.Paused -> {
                h.store.queue.value = listOf(1, 2)
                h.start()
                h.engine.playEpisode(1)
                run(h.player).untilPositionAtLeast(3_000)
                if (state == State.Paused) h.engine.sessionPlayer.pause()
                idle()
            }
            State.Ended -> {
                h.store.queue.value = listOf(3)
                h.start()
                h.engine.playEpisode(3)
                // The engine leaves STATE_ENDED within the same callback; wait for the stop it causes.
                run(h.player).untilPlayWhenReadyIs(false)
                idle()
            }
            State.Error -> {
                h.sources.brokenStreams += 1L
                h.store.queue.value = listOf(1, 2)
                h.start()
                h.engine.playEpisode(1)
                run(h.player).untilPlayerError()
                idle()
            }
        }
        return h
    }

    @Test
    fun everyCommandIsSafeInEveryState() {
        val failures = mutableListOf<String>()
        for (state in State.entries) {
            for ((name, command) in commands) {
                val h = enter(state)
                try {
                    command(h.engine)
                    idle()
                    // Invariants: the player holds at most current + next, and item 0 is the stored current.
                    assertTrue(h.player.mediaItemCount <= 2)
                    val current = h.store.current
                    if (h.player.mediaItemCount > 0) assertEquals(current?.toString(), h.player.getMediaItemAt(0).mediaId)
                } catch (t: Throwable) {
                    failures += "$name in $state: $t"
                } finally {
                    runCatching { h.release() }
                }
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
