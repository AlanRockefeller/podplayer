/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Feed dates (SPEC §4): RFC 822 and the ways publishers bend it, plus ISO 8601 (Atom, and RSS feeds that
 * use it anyway). Returns epoch milliseconds, or null for anything it can't read; the caller then uses
 * the first-seen time. The day name is ignored (it is often wrong), missing seconds and zones are allowed,
 * two-digit years are 1950–2049, and US zone names are known. Years outside 1970–2200 are not believed.
 */
object FeedDates {
    private const val MAX_LENGTH = 100
    private const val MIN_YEAR = 1970
    private const val MAX_YEAR = 2200

    private val iso = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?\s*([Zz]|[+-]\d{2}(?::?\d{2})?)?)?$""",
    )

    // [Day,] DD Mon YYYY [HH:MM[:SS[.fff]]] [zone]
    private val dayFirst = Regex(
        """^(?:[A-Za-z]+\.?,?\s+)?(\d{1,2})\s+([A-Za-z]+)\.?,?\s+(\d{4}|\d{2})(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?)?\s*(.*)$""",
    )

    // [Day,] Mon DD[,] YYYY [HH:MM[:SS]] [zone]
    private val monthFirst = Regex(
        """^(?:[A-Za-z]+\.?,?\s+)?([A-Za-z]+)\.?\s+(\d{1,2}),?\s+(\d{4})(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?)?\s*(.*)$""",
    )

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val fullMonths = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "sept",
        "october", "november", "december",
    )

    private val zones = mapOf(
        "gmt" to 0, "ut" to 0, "utc" to 0, "z" to 0,
        "edt" to -4, "est" to -5, "cdt" to -5, "cst" to -6, "mdt" to -6, "mst" to -7, "pdt" to -7, "pst" to -8,
        "akdt" to -8, "akst" to -9, "hst" to -10, "bst" to 1, "cet" to 1, "cest" to 2, "eet" to 2, "eest" to 3,
        "ist" to 5, "jst" to 9, "aest" to 10, "aedt" to 11,
    )
    private val numericZone = Regex("""^(?:gmt|utc)?([+-])(\d{2}):?(\d{2})?$""")
    private val alphaZone = Regex("""^[a-z]{1,5}$""")

    fun parse(raw: String?): Long? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        return try {
            parseIso(text) ?: parseRfc(text)
        } catch (_: DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseIso(text: String): Long? {
        val m = iso.matchEntire(text) ?: return null
        val g = m.groupValues
        val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
        val time = if (g[4].isEmpty()) LocalTime.MIDNIGHT else time(g[4], g[5], g[6]) ?: return null
        val offset = if (g[8].isEmpty()) 0 else zoneSeconds(g[8].lowercase()) ?: return null
        val fractionMs = if (g[7].isEmpty()) 0 else g[7].take(3).padEnd(3, '0').toLong()
        return toMillis(date, time, offset)?.plus(fractionMs)
    }

    private fun parseRfc(text: String): Long? {
        dayFirst.matchEntire(text)?.let { m ->
            val g = m.groupValues
            return assemble(day = g[1], month = g[2], year = g[3], hour = g[4], minute = g[5], second = g[6], zone = g[7])
        }
        monthFirst.matchEntire(text)?.let { m ->
            val g = m.groupValues
            return assemble(day = g[2], month = g[1], year = g[3], hour = g[4], minute = g[5], second = g[6], zone = g[7])
        }
        return null
    }

    private fun assemble(day: String, month: String, year: String, hour: String, minute: String, second: String, zone: String): Long? {
        val monthNumber = month(month) ?: return null
        val yearNumber = year.toInt().let { if (year.length == 2) (if (it < 50) 2000 + it else 1900 + it) else it }
        val date = LocalDate.of(yearNumber, monthNumber, day.toInt())
        val time = if (hour.isEmpty()) LocalTime.MIDNIGHT else time(hour, minute, second) ?: return null
        val offset = zoneSeconds(zone.removeParenthesized().trim().lowercase()) ?: return null
        return toMillis(date, time, offset)
    }

    private fun month(name: String): Int? {
        val lower = name.lowercase()
        if (lower.length > 3 && lower !in fullMonths) return null
        return months[lower.take(3)]
    }

    private fun time(hour: String, minute: String, second: String): LocalTime? {
        val h = hour.toInt()
        val m = minute.toInt()
        // Leap seconds (":60") are clamped rather than thrown away.
        val s = if (second.isEmpty()) 0 else second.toInt().let { if (it == 60) 59 else it }
        if (h > 23 || m > 59 || s > 59) return null
        return LocalTime.of(h, m, s)
    }

    /** Offset in seconds for a zone token, or null when it is a malformed number. Unknown names are UTC. */
    private fun zoneSeconds(zone: String): Int? {
        if (zone.isEmpty()) return 0
        zones[zone]?.let { return it * 3600 }
        numericZone.matchEntire(zone)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val hours = m.groupValues[2].toInt()
            val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (hours > 14 || minutes > 59) return null
            return sign * (hours * 3600 + minutes * 60)
        }
        if (alphaZone.matches(zone)) return 0
        return null
    }

    private fun String.removeParenthesized(): String = replace(Regex("""\([^)]*\)"""), "")

    private fun toMillis(date: LocalDate, time: LocalTime, offsetSeconds: Int): Long? {
        if (date.year !in MIN_YEAR..MAX_YEAR) return null
        return LocalDateTime.of(date, time).toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
    }
}
