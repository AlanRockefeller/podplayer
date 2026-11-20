/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.notes

/** Finds timestamps such as `12:34` and `1:02:03` in show-notes text (SPEC §5). */
object Timestamps {
    /** More than this in one text is a transcript, not chapter marks; the rest stay plain text. */
    const val MAX_PER_TEXT = 500

    class Found(val start: Int, val end: Int, val positionMs: Long)

    // h:mm:ss, or m:ss / mmm:ss. Not glued to letters, digits, colons or a decimal point, and not a time of day.
    private val pattern = Regex(
        """(?<![\p{L}\p{N}:.])(?:(\d{1,2}):([0-5]\d)|(\d{1,3})):([0-5]\d)(?![\p{L}\p{N}:]|[.,]\d)(?!\s?(?i:[ap]\.?m\b\.?))""",
    )

    fun find(text: String, durationMs: Long?): List<Found> {
        val found = ArrayList<Found>()
        for (m in pattern.findAll(text)) {
            val g = m.groupValues
            val seconds = if (g[1].isNotEmpty()) {
                g[1].toLong() * 3600 + g[2].toLong() * 60 + g[4].toLong()
            } else {
                g[3].toLong() * 60 + g[4].toLong()
            }
            val ms = seconds * 1000
            if (durationMs != null && ms > durationMs) continue
            found += Found(m.range.first, m.range.last + 1, ms)
            if (found.size == MAX_PER_TEXT) break
        }
        return found
    }
}
