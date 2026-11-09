/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.testing.Fixtures
import us.dikarya.podplayer.core.testing.SyntheticFeeds

/**
 * SPEC §14 hostile corpus, feed part: one test per fixture. LESSONS "Parser strictness": recover from
 * common publisher mistakes (bare `&`, bad dates, wrong encodings) inside the security limits.
 * The network items (enclosure or redirect to a private address, redirect loop) are refused by the gate;
 * see [EnclosureGateTest] and the `:core:net` tests.
 */
class HostileFeedCorpusTest {
    private val mib = 1024 * 1024

    @Test
    fun `entity-expansion bomb is refused quickly`() {
        lateinit var result: FeedParseResult
        val took = measureTime { result = parseFixture("corpus/feeds/entity-expansion-bomb.xml") }
        assertEquals(ParseProblem.EntityDeclaration, result.problem())
        assertTrue("took $took", took < 2.seconds)
    }

    @Test
    fun `external entity is refused`() {
        assertEquals(ParseProblem.EntityDeclaration, parseFixture("corpus/feeds/external-entity.xml").problem())
    }

    @Test
    fun `external parameter entity is refused`() {
        assertEquals(ParseProblem.EntityDeclaration, parseFixture("corpus/feeds/external-parameter-entity.xml").problem())
    }

    @Test
    fun `a DOCTYPE with even a harmless entity is refused`() {
        assertEquals(ParseProblem.EntityDeclaration, parseFixture("corpus/feeds/doctype-with-entities.xml").problem())
    }

    @Test
    fun `a DOCTYPE without entities is dropped and the feed parses`() {
        val result = parseFixture("corpus/feeds/doctype-without-entities.xml").success()
        assertEquals("Old School Radio", result.feed.title)
        assertEquals(1, result.feed.episodes.size)
        assertTrue(result.warnings.filterIsInstance<FeedWarning.Repaired>().single().repairs.doctypeRemoved)
    }

    @Test
    fun `a 25 MB feed is refused`() {
        val result = parseFeed(SyntheticFeeds.oversized(25 * mib))
        assertEquals(ParseProblem.TooLarge(20 * mib), result.problem())
    }

    @Test
    fun `one byte over the limit is refused, the limit itself is fine`() {
        val limits = XmlLimits(maxBytes = 4096)
        assertEquals(ParseProblem.TooLarge(4096), parseFeed(SyntheticFeeds.oversized(4097), limits = limits).problem())
        parseFeed(SyntheticFeeds.oversized(4096), limits = limits).success()
    }

    @Test
    fun `65-deep nesting is refused, 64 is fine`() {
        assertEquals(ParseProblem.TooDeep(64), parseFeed(SyntheticFeeds.nested(65)).problem())
        parseFeed(SyntheticFeeds.nested(64)).success()
    }

    @Test
    fun `very deep nesting is refused without a stack overflow`() {
        assertEquals(ParseProblem.TooDeep(64), parseFeed(SyntheticFeeds.nested(200_000)).problem())
    }

    @Test
    fun `bare ampersands and HTML entities are repaired`() {
        val result = parseFixture("corpus/feeds/bare-ampersands.xml").success()
        val feed = result.feed
        assertEquals("Tom & Jerry's Q&A", feed.title)
        assertEquals("https://show.example.com/?a=1&b=2", feed.link)
        assertEquals("Smith & Wesson Media", feed.author)
        assertEquals(
            "News & views — every week$NBSP© 2026. Fish &chips; AT&T; R&D © — &unknownentity; &",
            feed.description,
        )
        val item = feed.episodes.single()
        assertEquals("Rock & Roll … part 1", item.title)
        assertEquals("https://show.example.com/?p=1&x=2", item.guid)
        assertEquals("https://media.example.com/ep1.mp3?source=feed&id=42", item.enclosure?.url)
        assertEquals("CDATA keeps & and &amp; as written <b>bold</b>", item.notes)
        val repairs = result.warnings.filterIsInstance<FeedWarning.Repaired>().single().repairs
        assertTrue(repairs.bareAmpersands >= 8)
        assertEquals(4, repairs.htmlEntities)
    }

    @Test
    fun `invalid dates become null, common deviations still parse`() {
        val episodes = parseFixture("corpus/feeds/invalid-dates.xml").feed().episodes.associateBy { it.guid }
        assertEquals(6, episodes.size)
        for (guid in listOf("d1", "d2", "d3", "d4")) assertNull(guid, episodes.getValue(guid).publishedAt)
        assertEquals(millis("2026-10-05T20:00:00Z"), episodes.getValue("d5").publishedAt)
        assertEquals(millis("2026-10-05T20:00:00Z"), episodes.getValue("d6").publishedAt)
    }

    @Test
    fun `a wrong declared encoding is overruled when the bytes are clearly UTF-8`() {
        val feed = parseFixture("corpus/feeds/wrong-declared-encoding.xml").feed()
        assertEquals("Café Ünïcode – “quotes”", feed.title)
        assertEquals("Crème brûlée 🎙", feed.episodes.single().title)
    }

    @Test
    fun `Latin-1 bytes under a UTF-8 declaration still parse, with replacement characters`() {
        val bytes = SyntheticFeeds.accented("UTF-8").toByteArray(Charsets.ISO_8859_1)
        val feed = parseFeed(bytes).feed()
        assertEquals("Caf$REPLACEMENT cr${REPLACEMENT}me ${REPLACEMENT}ber", feed.title)
    }

    @Test
    fun `a legacy Latin-1 feed with a correct declaration parses`() {
        val bytes = SyntheticFeeds.accented("ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
        assertEquals("Café crème über", parseFeed(bytes).feed().title)
    }

    @Test
    fun `UTF-8 byte order mark`() {
        assertEquals("Byte Order Mark Show – é", parseFixture("corpus/feeds/utf8-bom.xml").feed().title)
    }

    @Test
    fun `UTF-16 byte order marks`() {
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = SyntheticFeeds.encoded(SyntheticFeeds.accented("UTF-16"), charset, bom = true)
            assertEquals("Café crème über", parseFeed(bytes).feed().title)
        }
    }

    @Test
    fun `duplicate GUIDs parse and raise the broken-feed warning`() {
        val result = parseFixture("corpus/feeds/duplicate-guids.xml").success()
        assertEquals(5, result.feed.episodes.size)
        val warning = result.warnings.filterIsInstance<FeedWarning.DuplicateGuids>().single()
        assertEquals(1, warning.guids)
        assertTrue(warning.validatorLinks.isNotEmpty())
        // The links must not carry the feed URL: feed URLs are secrets (CLAUDE.md).
        assertTrue(warning.validatorLinks.all { it.startsWith("https://") && '?' !in it })
    }

    @Test
    fun `HTML instead of RSS is reported as a web page`() {
        assertEquals(ParseProblem.HtmlPage, parseFixture("corpus/feeds/html-instead-of-rss.html").problem())
        assertEquals(ParseProblem.HtmlPage, parseFeed("$BOM\n  <HTML><BODY>Moved</BODY></HTML>").problem())
    }

    @Test
    fun `javascript and intent links are dropped from feed fields`() {
        val result = parseFixture("corpus/feeds/javascript-links.xml").success()
        val feed = result.feed
        assertNull(feed.link)
        assertNull(feed.imageUrl)
        val item = feed.episodes.single()
        assertNull(item.link)
        assertNull(item.imageUrl)
        assertEquals("https://media.example.com/js1.mp3", item.enclosure?.url)
        assertEquals(4, result.warnings.filterIsInstance<FeedWarning.UrlsRejected>().single().count)
        // The notes keep their raw HTML; the sanitizer handles them (ShowNotesTest).
        assertTrue(item.notes!!.contains("javascript:alert(document.cookie)"))
    }

    @Test
    fun `an enclosure on a private address is kept for the network gate to refuse`() {
        val result = parseFixture("corpus/feeds/enclosure-private-address.xml").success()
        val urls = result.feed.episodes.map { it.enclosure?.url }
        assertEquals(
            listOf(
                "http://192.168.0.1/admin/backup.mp3",
                "http://169.254.169.254/latest/meta-data/iam.mp3",
                "http://127.0.0.1:8080/ep.mp3",
                "http://[::1]/ep.mp3",
                "http://10.0.0.5/ep.mp3",
                // An ambiguous numeric host is refused by the URL policy already.
                null,
            ),
            urls,
        )
    }

    @Test
    fun `a document that is not a feed is reported with its root element`() {
        assertEquals(ParseProblem.NotAFeed("sitemap"), parseFixture("corpus/feeds/not-a-feed.xml").problem())
    }

    @Test
    fun `an undeclared itunes prefix is repaired`() {
        val result = parseFixture("corpus/feeds/undeclared-itunes-prefix.xml").success()
        assertEquals("Careless Publisher", result.feed.author)
        assertEquals("https://img.example.com/cover.jpg", result.feed.imageUrl)
        assertEquals(3_723_000L, result.feed.episodes.single().durationMs)
        assertEquals(listOf("itunes"), result.warnings.filterIsInstance<FeedWarning.Repaired>().single().repairs.namespacesAdded)
    }

    @Test
    fun `an undeclared unknown prefix is malformed, not a crash`() {
        assertTrue(parseFeed(rss(items = "<item><foo:bar>x</foo:bar></item>")).problem() is ParseProblem.Malformed)
    }

    @Test
    fun `a truncated feed is malformed`() {
        assertTrue(parseFixture("corpus/feeds/truncated.xml").problem() is ParseProblem.Malformed)
    }

    @Test
    fun `references to illegal characters are removed`() {
        val feed = parseFixture("corpus/feeds/illegal-character-references.xml").feed()
        assertEquals("Control characters removed too", feed.title)
        assertEquals("Tab and newline are fine", feed.episodes.single().title)
    }

    @Test
    fun `raw control characters are removed`() {
        // (A lone surrogate can't survive UTF-8 encoding; XmlPrepassTest covers it on decoded text.)
        val xml = rss(
            channel = "<title>A\u0001B\u001FC${NONCHARACTER}D\u000BE</title>",
            items = "<item><title>T</title><description><![CDATA[x\u0000y\u0008z]]></description></item>",
        )
        val feed = parseFeed(xml).feed()
        assertEquals("ABCDE", feed.title)
        assertEquals("xyz", feed.episodes.single().notes)
    }

    @Test
    fun `too many items are refused`() {
        val limits = XmlLimits(maxItems = 50)
        assertEquals(ParseProblem.TooManyItems(50), parseFeed(SyntheticFeeds.rss(51).toByteArray(), limits = limits).problem())
        assertEquals(50, parseFeed(SyntheticFeeds.rss(50).toByteArray(), limits = limits).feed().episodes.size)
    }

    @Test
    fun `a tag with too many attributes is refused`() {
        val attributes = (1..1000).joinToString(" ") { "a$it=\"x\"" }
        assertEquals(ParseProblem.TooManyAttributes(256), parseFeed(rss(items = "<item $attributes/>")).problem())
    }

    @Test
    fun `empty and garbage input fail cleanly`() {
        for (body in listOf("", " ", "null", "{\"json\": true}", "<", "<rss", "<<<>>>", "\u0000\u0000")) {
            assertTrue(body, parseFeed(body).problem() is ParseProblem.Malformed || parseFeed(body).problem() is ParseProblem.NotAFeed)
        }
    }

    @Test
    fun `nothing in the corpus throws`() {
        val files = listOf(
            "entity-expansion-bomb.xml", "external-entity.xml", "external-parameter-entity.xml",
            "doctype-with-entities.xml", "doctype-without-entities.xml", "bare-ampersands.xml", "invalid-dates.xml",
            "wrong-declared-encoding.xml", "utf8-bom.xml", "duplicate-guids.xml", "html-instead-of-rss.html",
            "javascript-links.xml", "enclosure-private-address.xml", "not-a-feed.xml", "undeclared-itunes-prefix.xml",
            "truncated.xml", "illegal-character-references.xml",
        )
        for (file in files) {
            val bytes = Fixtures.bytes("corpus/feeds/$file")
            // Every prefix of every fixture: truncation at any point must give a typed result.
            for (cut in bytes.indices step 7) parseFeed(bytes.copyOf(cut))
        }
        assertFalse(files.isEmpty())
    }
}
