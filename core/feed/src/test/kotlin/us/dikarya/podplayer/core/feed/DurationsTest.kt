/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/** `itunes:duration` as publishers really write it. */
class DurationsTest {
    @Test
    fun `accepted forms`() {
        val cases = mapOf(
            "3723" to 3_723_000L,
            "3723.5" to 3_723_500L,
            "0" to 0L,
            "45:10" to 2_710_000L,
            "5:07" to 307_000L,
            "62:30" to 3_750_000L,
            "1:02:03" to 3_723_000L,
            "01:02:03" to 3_723_000L,
            "01:02:03.250" to 3_723_250L,
            "1:02:03,250" to 3_723_250L,
            " 1:00:00 " to 3_600_000L,
            "100:00:00" to 360_000_000L,
        )
        for ((text, ms) in cases) assertEquals(text, ms, Durations.parseMs(text))
    }

    @Test
    fun `rejected forms`() {
        val cases = listOf(
            "", " ", "about an hour", "-5", "1:-2", "1:60", "1:02:60", "1:60:00", "1::2", ":30", "1:2:3:4",
            "1e3", "NaN", "Infinity", "0x10", "99999999999999999999", "9999999", "1.2.3", "１２", "1:02:03.",
        )
        for (text in cases) assertEquals(text, null, Durations.parseMs(text))
    }

    @Test
    fun `durations longer than a week are not believed`() {
        assertEquals(null, Durations.parseMs((7 * 24 * 3600 + 1).toString()))
        assertEquals(7 * 24 * 3600 * 1000L, Durations.parseMs((7 * 24 * 3600).toString()))
    }

    @Test
    fun `seconds with fractions for soundbites`() {
        assertEquals(73_000L, Durations.secondsToMs("73.0"))
        assertEquals(60_500L, Durations.secondsToMs("60.5"))
        assertEquals(null, Durations.secondsToMs("-5"))
        assertEquals(null, Durations.secondsToMs("x"))
    }
}
