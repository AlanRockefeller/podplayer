/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LESSONS "Position jumps": a move the player didn't report is logged as unexplained. */
class JumpDetectorTest {
    @Test
    fun normalProgressAtAnySpeedIsNotAJump() {
        assertFalse(JumpDetector.isUnexplained(fromMs = 10_000, elapsedMs = 1_000, toMs = 11_000, speed = 1f))
        assertFalse(JumpDetector.isUnexplained(fromMs = 10_000, elapsedMs = 10_000, toMs = 30_000, speed = 2f))
        assertFalse("small timer jitter", JumpDetector.isUnexplained(fromMs = 0, elapsedMs = 1_000, toMs = 2_500, speed = 1f))
    }

    @Test
    fun skippingAheadOrRepeatingIsAJump() {
        assertTrue(JumpDetector.isUnexplained(fromMs = 10_000, elapsedMs = 1_000, toMs = 40_000, speed = 1f))
        assertTrue(JumpDetector.isUnexplained(fromMs = 600_000, elapsedMs = 1_000, toMs = 540_000, speed = 1f))
    }
}
