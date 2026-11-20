/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import us.dikarya.podplayer.core.feed.xml.Ticker
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.testing.Fixtures

/** Reader edge cases from review: repeated fields, long URLs, the time budget and odd references. */
class FeedReaderEdgeCaseTest {
    @Test
    fun `a repeated description is consumed whole, so its children are not read as item fields`() {
        val items = "<item><guid>g</guid><description>first</description>" +
            "<description>second <enclosure url=\"https://evil.example/planted.mp3\" type=\"audio/mpeg\"/></description>" +
            "<enclosure url=\"https://media.example/real.mp3\" type=\"audio/mpeg\"/></item>"
        val episode = parseFeed(rss(items = items)).feed().episodes.single()
        assertEquals("https://media.example/real.mp3", episode.enclosure!!.url)
        assertEquals("first", episode.notes)
    }

    @Test
    fun `a repeated channel image is consumed whole, so its title is not the channel's`() {
        val channel = "<image><url>https://img.example/a.jpg</url></image>" +
            "<image><url>https://img.example/b.jpg</url><title>Planted</title><link>https://evil.example/</link></image>" +
            "<title>Real Show</title><link>https://show.example/</link>"
        val feed = parseFeed(rss(channel = channel)).feed()
        assertEquals("Real Show", feed.title)
        assertEquals("https://show.example/", feed.link)
        assertEquals("https://img.example/a.jpg", feed.imageUrl)
    }

    @Test
    fun `repeated Atom fields are consumed whole too`() {
        val atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>A</title>" +
            "<title>B<link rel=\"alternate\" href=\"https://evil.example/\"/></title>" +
            "<link rel=\"alternate\" href=\"https://show.example/\"/></feed>"
        val feed = parseFeed(atom).feed()
        assertEquals("A", feed.title)
        assertEquals("https://show.example/", feed.link)
    }

    @Test
    fun `a long URL in element text is kept exactly`() {
        val long = "https://img.example/cover.jpg?sig=" + "a".repeat(3_000)
        val feed = parseFeed(rss(channel = "<title>S</title><image><url>$long</url></image>")).feed()
        assertEquals(long, feed.imageUrl)
    }

    @Test
    fun `a URL longer than the URL limit is rejected, not shortened into another URL`() {
        val tooLong = "https://img.example/cover.jpg?sig=" + "a".repeat(UrlPolicy.MAX_URL_LENGTH + 2_000)
        val result = parseFeed(rss(channel = "<title>S</title><image><url>$tooLong</url></image>")).success()
        assertNull(result.feed.imageUrl)
        assertTrue("${result.warnings}", result.warnings.any { it is FeedWarning.UrlsRejected })
    }

    @Test
    fun `an uppercase hex character reference does not break the feed`() {
        val result = parseFeed(rss(channel = "<title>Q&#X26;A</title>"))
        assertEquals("Q&A", result.feed().title)
    }

    @Test
    fun `a budget that runs out after the last time check still rejects the feed`() {
        // A small feed has fewer events than the periodic time check needs, so the last stretch goes unchecked
        // unless the parser looks at the clock before it reports success.
        val clock = object : Ticker {
            var now = 0L

            override fun nanos() = now
        }
        val parsers = XmlParserProvider {
            val inner = Fixtures.xmlPullParser()
            object : XmlPullParser by inner {
                override fun next(): Int {
                    val event = inner.next()
                    // The budget runs out while the root element closes.
                    if (event == XmlPullParser.END_TAG && inner.depth == 1) clock.now = 11.seconds.inWholeNanoseconds
                    return event
                }
            }
        }
        val result = FeedParser(parsers, XmlLimits(), clock).parse(rss().toByteArray())
        assertEquals(ParseProblem.TimeLimit, result.problem())
    }

    @Test
    fun `a feed parsed within its budget is still a success`() {
        val clock = Ticker { 0L }
        val result = FeedParser(kxml, XmlLimits(), clock).parse(rss().toByteArray())
        assertEquals("Show", result.feed().title)
    }
}
