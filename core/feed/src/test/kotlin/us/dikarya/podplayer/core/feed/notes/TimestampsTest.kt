/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC §5: timestamps like `12:34` in notes become seek links within the same episode. */
class TimestampsTest {
    private fun positions(text: String, durationMs: Long? = null) = Timestamps.find(text, durationMs).map { it.positionMs }

    @Test
    fun `minutes and seconds, hours too`() {
        assertEquals(listOf(754_000L), positions("12:34"))
        assertEquals(listOf(3_723_000L), positions("1:02:03"))
        assertEquals(listOf(3_723_000L), positions("01:02:03"))
        assertEquals(listOf(0L), positions("00:00"))
        assertEquals(listOf(307_000L), positions("5:07"))
        assertEquals(listOf(7_200_000L), positions("120:00"))
    }

    @Test
    fun `brackets and punctuation around them`() {
        assertEquals(listOf(754_000L, 300_000L, 60_000L, 120_000L), positions("(00:12:34) [5:00] 01:00, 02:00."))
        assertEquals(listOf(754_000L), positions("Intro—12:34—Topic"))
    }

    @Test
    fun `the match covers exactly the timestamp`() {
        val text = "See (12:34) now"
        val found = Timestamps.find(text, null).single()
        assertEquals("12:34", text.substring(found.start, found.end))
    }

    @Test
    fun `things that only look like timestamps`() {
        for (text in listOf(
            "10:30 am", "10:30am", "10:30 PM", "10:30p.m.", "3:7", "99:99", "12:345", "1:2:3", "16:9", "1:60:00",
            "a12:34", "12:34b", "12:34:56:78", "1:02:03:04", "12:34.5", "12.12:34", "v1:00", "https://x.example.com:8080/",
        )) {
            assertEquals(text, emptyList<Long>(), positions(text))
        }
    }

    @Test
    fun `a known limitation - verse references read as timestamps`() {
        // "John 3:16" is three minutes sixteen. Tapping it seeks; it never leaves the episode.
        assertEquals(listOf(196_000L), positions("John 3:16"))
    }

    @Test
    fun `positions past the duration are skipped`() {
        assertEquals(listOf(60_000L), positions("01:00 59:00", durationMs = 30 * 60_000L))
        assertEquals(listOf(60_000L, 3_540_000L), positions("01:00 59:00", durationMs = null))
    }

    @Test
    fun `many timestamps are capped`() {
        val text = (0 until 5_000).joinToString(" ") { "%d:%02d".format(it / 60, it % 60) }
        assertEquals(Timestamps.MAX_PER_TEXT, positions(text).size)
    }
}
