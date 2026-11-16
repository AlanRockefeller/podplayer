/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §10 (owner, 2026-11-15): reaching 98% of an episode counts as played. */
class PlayedRuleTest {
    @Test
    fun ninetyEightPercentIsTheBoundary() {
        assertFalse(PlaybackRecord.countsAsPlayed(97_999, 100_000))
        assertTrue(PlaybackRecord.countsAsPlayed(98_000, 100_000))
        assertTrue(PlaybackRecord.countsAsPlayed(100_000, 100_000))
        assertTrue("past the end still counts", PlaybackRecord.countsAsPlayed(120_000, 100_000))
    }

    @Test
    fun anUnknownOrZeroLengthNeverCounts() {
        assertFalse(PlaybackRecord.countsAsPlayed(5_000, null))
        assertFalse(PlaybackRecord.countsAsPlayed(0, 0))
        assertFalse(PlaybackRecord.countsAsPlayed(5_000, -1))
    }
}
