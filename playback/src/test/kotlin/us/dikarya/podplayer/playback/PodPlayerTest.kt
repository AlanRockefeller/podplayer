/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Review PR #5: what the session's player tells controllers, and what an ignored Play leaves behind. */
@RunWith(AndroidJUnit4::class)
class PodPlayerTest {
    private val h = Harness()

    @After
    fun tearDown() = h.release()

    @Test
    fun theCommandsAControllerIsToldAboutIncludeNextOnTheLastEpisode() {
        val reported = mutableListOf<Player.Commands>()
        h.engine.sessionPlayer.addListener(
            object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    reported += availableCommands
                }
            },
        )
        h.store.add(1)
        h.start()
        h.engine.playEpisode(1)
        run(h.player).untilState(Player.STATE_READY)
        idle()
        // One seekable episode and nothing after it: "next" skips forward within it, so it is offered.
        assertTrue(h.engine.sessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        val last = reported.last()
        assertTrue("the event says the same as the query: $last", last.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(last.contains(Player.COMMAND_SEEK_FORWARD))
    }

    @Test
    fun aListenerAddedThroughTheSessionPlayerHearsEveryOtherEventToo() {
        var heard = 0
        var playing = false
        var item: String? = null
        h.engine.sessionPlayer.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    heard++
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    item = mediaItem?.mediaId
                }
            },
        )
        h.store.add(1)
        h.start()
        h.engine.playEpisode(1)
        run(h.player).untilState(Player.STATE_READY)
        idle()
        assertTrue("onEvents reached the listener", heard > 0)
        assertTrue("onIsPlayingChanged reached the listener", playing)
        assertEquals("1", item)
    }

    @Test
    fun aListenerAddedThroughTheSessionPlayerCanBeRemoved() {
        var heard = 0
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                heard++
            }
        }
        h.engine.sessionPlayer.addListener(listener)
        h.engine.sessionPlayer.removeListener(listener)
        h.store.add(1)
        h.start()
        h.engine.playEpisode(1)
        idle()
        assertEquals(0, heard)
    }

    @Test
    fun anIgnoredAutomaticPlayLeavesAnEndedEpisodeAtItsEnd() {
        h.store.add(1, durationMs = 10_000)
        h.store.queue.value = listOf(1)
        h.start()
        h.engine.playEpisode(1)
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertEquals(Player.STATE_ENDED, h.player.playbackState)
        val endedAt = h.player.currentPosition
        h.engine.playGate = PlayGate { "automatic Play after the car connected" }
        // What the session does with a Play: on an ended episode, Media3 seeks to its start before calling play().
        Util.handlePlayButtonAction(h.engine.sessionPlayer)
        idle()
        assertFalse(h.player.playWhenReady)
        assertEquals("still at its end", endedAt, h.player.currentPosition)
        // The owner's own Play still starts it over.
        h.engine.playGate = PlayGate { null }
        Util.handlePlayButtonAction(h.engine.sessionPlayer)
        idle()
        assertTrue(h.player.playWhenReady)
        assertTrue("from the start: ${h.player.currentPosition}", h.player.currentPosition < endedAt)
    }
}
