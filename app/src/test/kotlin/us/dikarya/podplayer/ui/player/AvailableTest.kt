/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The seek bar's "ready to play" mark (owner, 2026-11-18): how far the stream has buffered, or all of a download. */
class AvailableTest {
    @Test
    fun aStreamShowsHowFarItHasBuffered() {
        assertEquals(0.5f, availableFraction(positionMs = 600_000, bufferedMs = 1_800_000, durationMs = 3_600_000, downloaded = false)!!, 0.0001f)
    }

    @Test
    fun aDownloadIsAllThere() {
        assertEquals(1f, availableFraction(positionMs = 600_000, bufferedMs = 660_000, durationMs = 3_600_000, downloaded = true)!!, 0.0001f)
    }

    @Test
    fun theMarkIsNeverBehindThePositionNorPastTheEnd() {
        assertEquals(0.25f, availableFraction(positionMs = 900_000, bufferedMs = 0, durationMs = 3_600_000, downloaded = false)!!, 0.0001f)
        assertEquals(1f, availableFraction(positionMs = 900_000, bufferedMs = 9_000_000, durationMs = 3_600_000, downloaded = false)!!, 0.0001f)
        assertEquals(1f, availableFraction(positionMs = 9_000_000, bufferedMs = 9_000_000, durationMs = 3_600_000, downloaded = false)!!, 0.0001f)
    }

    @Test
    fun withoutALengthThereIsNoMark() {
        assertNull(availableFraction(positionMs = 0, bufferedMs = 60_000, durationMs = null, downloaded = false))
        assertNull(availableFraction(positionMs = 0, bufferedMs = 60_000, durationMs = 0, downloaded = true))
    }
}
