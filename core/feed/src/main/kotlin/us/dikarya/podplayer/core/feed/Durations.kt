/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

/** `itunes:duration` and Podcasting 2.0 second values, as publishers really write them. */
object Durations {
    /** Longer than this is a publisher error, not an episode. */
    const val MAX_MS: Long = 7L * 24 * 3600 * 1000

    private val seconds = Regex("""^(\d{1,7})(?:[.,](\d{1,3})\d*)?$""")
    private val clock = Regex("""^(?:(\d{1,3}):)?(\d{1,4}):(\d{2})(?:[.,](\d{1,3})\d*)?$""")

    /** Seconds (`3723`, `3723.5`), `mm:ss` or `hh:mm:ss`, with an optional fraction; anything else is null. */
    fun parseMs(raw: String?): Long? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.length > 20) return null
        seconds.matchEntire(text)?.let { m ->
            return bounded(m.groupValues[1].toLong() * 1000 + fraction(m.groupValues[2]))
        }
        val m = clock.matchEntire(text) ?: return null
        val (h, mm, ss, frac) = m.destructured
        val minutes = mm.toLong()
        val secs = ss.toLong()
        if (secs > 59) return null
        // With hours, minutes must be a clock value; without, "62:30" is 62 minutes.
        if (h.isNotEmpty() && minutes > 59) return null
        val hours = if (h.isEmpty()) 0 else h.toLong()
        return bounded(((hours * 60 + minutes) * 60 + secs) * 1000 + fraction(frac))
    }

    /** A non-negative decimal number of seconds (`73.0`), as milliseconds. */
    fun secondsToMs(raw: String?): Long? {
        val m = seconds.matchEntire(raw?.trim() ?: return null) ?: return null
        return bounded(m.groupValues[1].toLong() * 1000 + fraction(m.groupValues[2]))
    }

    private fun fraction(digits: String): Long = if (digits.isEmpty()) 0 else digits.padEnd(3, '0').toLong()

    private fun bounded(ms: Long): Long? = ms.takeIf { it in 0..MAX_MS }
}
