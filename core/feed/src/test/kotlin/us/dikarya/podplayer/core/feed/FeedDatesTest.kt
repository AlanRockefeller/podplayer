/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SPEC §4: RFC 822 plus common deviations; unparseable dates become null. */
class FeedDatesTest {
    private val expected = millis("2026-10-05T13:00:00Z")

    @Test
    fun `RFC 822 and its common deviations`() {
        val cases = listOf(
            "Mon, 05 Oct 2026 13:00:00 GMT",
            "Mon, 05 Oct 2026 13:00:00 +0000",
            "Mon, 05 Oct 2026 13:00:00 -0000",
            "Mon, 05 Oct 2026 13:00:00 UT",
            "Mon, 05 Oct 2026 13:00:00 UTC",
            "Mon, 05 Oct 2026 13:00:00 Z",
            "Mon, 05 Oct 2026 13:00:00",
            "Mon, 5 Oct 2026 13:00:00 GMT",
            "05 Oct 2026 13:00:00 GMT",
            "Mon 05 Oct 2026 13:00:00 GMT",
            "Monday, 05 October 2026 13:00:00 GMT",
            "Tue, 05 Oct 2026 13:00:00 GMT",
            "Mon, 05 Oct 2026 13:00 GMT",
            "mon, 05 oct 2026 13:00:00 gmt",
            "Mon,  05  Oct  2026  13:00:00  GMT",
            "Mon, 05 Oct 26 13:00:00 GMT",
            "Mon, 05 Sept 2026 13:00:00 GMT".replace("Sept", "Oct."),
            "Mon, 05 Oct 2026 15:00:00 +0200",
            "Mon, 05 Oct 2026 15:00:00 +02:00",
            "Mon, 05 Oct 2026 18:30:00 +0530",
            "Mon, 05 Oct 2026 09:00:00 EDT",
            "Mon, 05 Oct 2026 08:00:00 EST",
            "Mon, 05 Oct 2026 08:00:00 CDT",
            "Mon, 05 Oct 2026 07:00:00 CST",
            "Mon, 05 Oct 2026 07:00:00 MDT",
            "Mon, 05 Oct 2026 06:00:00 MST",
            "Mon, 05 Oct 2026 06:00:00 PDT",
            "Mon, 05 Oct 2026 05:00:00 PST",
            "Mon, 05 Oct 2026 13:00:00 GMT+0000",
            "Mon, 05 Oct 2026 13:00:00.000 GMT",
            "Mon, 05 Oct 2026 13:00:00 GMT (Coordinated Universal Time)",
            "Oct 05, 2026 13:00:00 GMT",
            "October 5 2026 13:00:00 +0000",
            "  Mon, 05 Oct 2026 13:00:00 GMT  ",
        )
        for (case in cases) assertEquals(case, expected, FeedDates.parse(case))
    }

    @Test
    fun `ISO 8601, as Atom and some RSS feeds use`() {
        val cases = listOf(
            "2026-10-05T13:00:00Z",
            "2026-10-05T13:00:00.000Z",
            "2026-10-05T15:00:00+02:00",
            "2026-10-05T15:00:00+0200",
            "2026-10-05T13:00:00",
            "2026-10-05 13:00:00",
            "2026-10-05T13:00Z",
            "2026-10-05t13:00:00z",
        )
        for (case in cases) assertEquals(case, expected, FeedDates.parse(case))
    }

    @Test
    fun `date-only values are midnight UTC`() {
        assertEquals(millis("2026-10-05T00:00:00Z"), FeedDates.parse("2026-10-05"))
        assertEquals(millis("2026-10-05T00:00:00Z"), FeedDates.parse("05 Oct 2026"))
        assertEquals(millis("2026-10-05T00:00:00Z"), FeedDates.parse("Mon, 05 Oct 2026"))
    }

    @Test
    fun `two-digit years`() {
        assertEquals(millis("1999-10-05T13:00:00Z"), FeedDates.parse("Tue, 05 Oct 99 13:00:00 GMT"))
        assertEquals(millis("2005-10-05T13:00:00Z"), FeedDates.parse("Wed, 05 Oct 05 13:00:00 GMT"))
    }

    @Test
    fun `unknown alphabetic zones are read as UTC`() {
        assertEquals(expected, FeedDates.parse("Mon, 05 Oct 2026 13:00:00 XYZ"))
    }

    @Test
    fun `garbage becomes null`() {
        val cases = listOf(
            "", " ", "sometime last week", "Fri, 32 Oct 2020 10:00:00 GMT", "Fri, 30 Feb 2020 10:00:00 GMT",
            "Fri, 10 Foo 2020 10:00:00 GMT", "Fri, 10 Oct 2020 25:00:00 GMT", "Fri, 10 Oct 2020 10:61:00 GMT",
            "Sat, 01 Jan 99999 00:00:00 GMT", "Sat, 01 Jan 1066 00:00:00 GMT", "2026-13-01", "2026-10-05T13:00:00+25:00",
            "Mon, 05 Oct 2026 13:00:00 +99999", "0", "-1", "1759669200", "1".repeat(10_000),
            "Mon, 05 Oct 2026 13:00:00 " + "Z".repeat(10_000),
        )
        for (case in cases) assertNull(case.take(60), FeedDates.parse(case))
    }

    @Test
    fun `leap seconds are clamped instead of dropped`() {
        assertEquals(millis("2016-12-31T23:59:59Z"), FeedDates.parse("Sat, 31 Dec 2016 23:59:60 GMT"))
    }
}
