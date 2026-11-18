/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner's phone, 2026-11-17: a stream cut short played on in silence, its position advancing with no audio
 * going out. The watch sees the position move on while no audio buffer reaches the output.
 */
class SilenceWatchTest {
    private val watch = SilenceWatch(limitMs = 15_000)

    @Test
    fun audioGoingOutIsNeverSilence() {
        for (second in 0..120) assertNull(watch.check(positionMs = second * 1_000L, renderedBuffers = second * 40))
    }

    @Test
    fun theTimeMovingOnWithoutAudioIsCaughtWithWhereTheSoundStopped() {
        assertNull(watch.check(600_000, renderedBuffers = 100))
        assertNull(watch.check(601_000, renderedBuffers = 140))
        // From 601 s, nothing more reaches the output.
        assertNull(watch.check(610_000, renderedBuffers = 140))
        assertEquals(601_000L, watch.check(616_000, renderedBuffers = 140))
        // Reported once; then it watches afresh.
        assertNull(watch.check(617_000, renderedBuffers = 140))
    }

    @Test
    fun aSeekResetsTheWatch() {
        assertNull(watch.check(10_000, renderedBuffers = 5))
        watch.reset()
        // A seek 60 s ahead: the first look after it is a new baseline, not 60 s of silence.
        assertNull(watch.check(70_000, renderedBuffers = 5))
        assertNull(watch.check(72_000, renderedBuffers = 90))
    }

    @Test
    fun noCountersMeansNoJudgement() {
        assertNull(watch.check(0, renderedBuffers = null))
        assertNull(watch.check(60_000, renderedBuffers = null))
    }

    @Test
    fun goingBackwardsStartsTheWatchAgain() {
        assertNull(watch.check(50_000, renderedBuffers = 7))
        assertNull(watch.check(10_000, renderedBuffers = 7))
        assertNull(watch.check(20_000, renderedBuffers = 7))
        assertEquals(10_000L, watch.check(25_000, renderedBuffers = 7))
    }
}
