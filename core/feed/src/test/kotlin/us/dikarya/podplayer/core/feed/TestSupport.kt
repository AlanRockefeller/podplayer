/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import java.time.Instant
import org.junit.Assert.fail
import us.dikarya.podplayer.core.feed.xml.Ticker
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.testing.Fixtures

/** kxml2 stands in for the platform parser, as SPEC §4 says. */
internal val kxml = XmlParserProvider { Fixtures.xmlPullParser() }

internal fun parseFeed(
    body: ByteArray,
    contentType: String? = null,
    limits: XmlLimits = XmlLimits(),
    ticker: Ticker = Ticker.SYSTEM,
): FeedParseResult = FeedParser(kxml, limits, ticker).parse(body, contentType)

internal fun parseFeed(xml: String): FeedParseResult = parseFeed(xml.toByteArray(Charsets.UTF_8))

internal fun parseFixture(path: String): FeedParseResult = parseFeed(Fixtures.bytes(path))

internal fun FeedParseResult.success(): FeedParseResult.Success =
    this as? FeedParseResult.Success ?: fail("Expected a parsed feed, got $this") as Nothing

internal fun FeedParseResult.feed(): ParsedFeed = success().feed

internal fun FeedParseResult.problem(): ParseProblem =
    (this as? FeedParseResult.Failure ?: fail("Expected a failure, got $this") as Nothing).problem

internal fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

// Invisible characters, spelled as code points so the test sources stay readable.
internal val BOM = Char(0xFEFF).toString()
internal val NONCHARACTER = Char(0xFFFE).toString()
internal val REPLACEMENT = Char(0xFFFD).toString()
internal val NBSP = Char(0xA0).toString()

/** A clock that moves [step] nanoseconds every time it is read. */
internal class FakeTicker(private val step: Long) : Ticker {
    private var now = 0L

    override fun nanos(): Long {
        now += step
        return now
    }
}

/** A small RSS document around [items], for focused tests. */
internal fun rss(channel: String = "<title>Show</title>", items: String = "", namespaces: String = ""): String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\" " +
        "xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" " +
        "xmlns:podcast=\"https://podcastindex.org/namespace/1.0\" " +
        "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\" $namespaces>" +
        "<channel>$channel$items</channel></rss>"
