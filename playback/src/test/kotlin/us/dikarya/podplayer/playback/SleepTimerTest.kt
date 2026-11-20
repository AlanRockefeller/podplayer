/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §10 and LESSONS "Sleep timer": the timer lives in the service, fades for 10 s, stops at a boundary, and
 * counts listening time only. The fake clock can run playback past a requested position, so these tests watch
 * the volume changes as they happen instead of sampling at a position.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerTest {
    private val h = Harness()

    /** (position, volume) at every volume change. */
    private val volumes = mutableListOf<Pair<Long, Float>>()

    private val volumeRecorder = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) {
            volumes += h.player.currentPosition to volume
        }
    }

    @After
    fun tearDown() = h.release()

    private fun remaining() = (h.engine.sleepTimer.value as SleepTimerState.Minutes).remainingMs

    /** Starts a long episode playing and returns the position the timer is set at. */
    private fun playingWithTimer(minutes: Int): Long {
        h.store.add(1, durationMs = 3_600_000)
        h.start()
        h.engine.playEpisode(1)
        run(h.player).untilPositionAtLeast(1_000)
        idle()
        h.player.addListener(volumeRecorder)
        val setAt = h.player.currentPosition
        h.engine.sleepAfterMinutes(minutes)
        return setAt
    }

    @Test
    fun aMinutesTimerFadesForTheLastTenSecondsThenPausesAndRestoresTheVolume() {
        val setAt = playingWithTimer(5)
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        val stopAt = setAt + 5 * 60_000L
        assertTrue("stopped at ${h.player.currentPosition}, expected about $stopAt", h.player.currentPosition in (stopAt - 1_000)..(stopAt + 1_000))
        val fade = volumes.filter { it.second < 1.0f }
        assertTrue("the volume stepped down: $volumes", fade.size >= 20)
        assertTrue("the fade began in the last 10 s: ${fade.first()}", fade.first().first >= stopAt - 10_000 - 500)
        assertEquals("each step quieter", fade.map { it.second }, fade.map { it.second }.sortedDescending())
        assertEquals("the volume is back to normal afterwards", 1.0f, h.player.volume)
        assertEquals(SleepTimerState.Off, h.engine.sleepTimer.value)
        assertTrue("the stop position was saved", h.store.positions[1]!! >= stopAt - 1_000)
    }

    @Test
    fun pausingDoesNotUseUpTheTimer() {
        playingWithTimer(5)
        run(h.player).untilPositionAtLeast(61_000)
        h.player.pause()
        idle()
        val left = remaining()
        assertTrue("some listening was counted: $left", left < 5 * 60_000L)
        h.clock.advanceTime(30 * 60_000)
        idle()
        assertEquals("half an hour paused (a phone call) costs nothing", left, remaining())
        assertTrue(h.player.volume == 1.0f)
    }

    @Test
    fun cancellingDuringTheFadeRestoresTheVolumeAndKeepsPlaying() {
        val setAt = playingWithTimer(5)
        // Cancel at the first quieter step, from the main thread as our screen would.
        h.player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                if (volume < 1.0f && h.engine.sleepTimer.value is SleepTimerState.Minutes) h.engine.cancelSleepTimer()
            }
        })
        run(h.player).untilPositionAtLeast(setAt + 5 * 60_000L + 30_000)
        idle()
        assertTrue("a fade began", volumes.any { it.second < 1.0f })
        assertEquals(1.0f, h.player.volume)
        assertTrue("not stopped by the cancelled timer", h.player.playWhenReady)
        assertEquals(SleepTimerState.Off, h.engine.sleepTimer.value)
    }

    /** The owner's phone, 2026-11-18: the next episode, saved at 34 s, was left at 0 after the timer stopped. */
    @Test
    fun endOfEpisodeLeavesTheNextEpisodeAtItsSavedPosition() {
        h.store.add(1, durationMs = 60_000)
        h.store.add(2)
        h.store.positions[2] = 34_000
        h.store.queue.value = listOf(1, 2)
        h.start()
        h.engine.playEpisode(1)
        idle()
        h.engine.sleepAtEndOfEpisode()
        idle()
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertEquals("2", h.player.currentMediaItem!!.mediaId)
        assertEquals(34_000L, h.player.currentPosition)
        assertFalse(h.player.playWhenReady)
    }

    @Test
    fun endOfEpisodeStopsAtTheBoundaryWithTheNextEpisodeReady() {
        h.store.add(1, durationMs = 60_000)
        h.store.add(2)
        h.store.queue.value = listOf(1, 2)
        h.start()
        h.engine.playEpisode(1)
        idle()
        h.engine.sleepAtEndOfEpisode()
        idle()
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertTrue(1L in h.store.played)
        assertEquals(listOf(2L), h.store.queue.value)
        assertEquals(2L, h.store.current)
        assertEquals("2", h.player.currentMediaItem!!.mediaId)
        assertEquals(0L, h.player.currentPosition)
        assertFalse(h.player.playWhenReady)
        assertEquals(SleepTimerState.Off, h.engine.sleepTimer.value)
        // The next play continues normally, without pausing at the next boundary.
        h.engine.sessionPlayer.play()
        idle()
        assertTrue(h.player.playWhenReady)
        assertFalse(h.player.pauseAtEndOfMediaItems)
    }

    @Test
    fun endOfEpisodeOnTheLastEpisodeJustStops() {
        h.store.add(1, durationMs = 30_000)
        h.store.queue.value = listOf(1)
        h.start()
        h.engine.playEpisode(1)
        idle()
        h.engine.sleepAtEndOfEpisode()
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertTrue(1L in h.store.played)
        assertEquals(SleepTimerState.Off, h.engine.sleepTimer.value)
    }

    @Test
    fun minutesAreHeldToTheSpecRange() {
        h.store.add(1)
        h.start()
        h.engine.sleepAfterMinutes(1)
        assertEquals(5 * 60_000L, remaining())
        h.engine.sleepAfterMinutes(10_000)
        assertEquals(120 * 60_000L, remaining())
    }
}
