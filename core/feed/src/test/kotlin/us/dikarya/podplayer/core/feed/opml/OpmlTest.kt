/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.opml

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import us.dikarya.podplayer.core.feed.NONCHARACTER
import us.dikarya.podplayer.core.feed.ParseProblem
import us.dikarya.podplayer.core.feed.kxml
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.net.UrlProblem
import us.dikarya.podplayer.core.testing.Fixtures

/** SPEC §4 OPML: same XML safety rules; import gives a preview, export writes all subscriptions. */
class OpmlTest {
    private fun parse(bytes: ByteArray, limits: XmlLimits = XmlLimits()) = OpmlParser(kxml, limits).parse(bytes)

    private fun parse(text: String) = parse(text.toByteArray())

    private fun OpmlParseResult.success(): OpmlParseResult.Success =
        this as? OpmlParseResult.Success ?: fail("Expected success, got $this") as Nothing

    private fun OpmlParseResult.problem(): ParseProblem =
        (this as? OpmlParseResult.Failure ?: fail("Expected failure, got $this") as Nothing).problem

    @Test
    fun `nested categories are flattened in document order`() {
        val result = parse(Fixtures.bytes("samples/opml/nested-categories.opml")).success()
        assertEquals("Exported subscriptions", result.title)
        assertEquals(
            listOf(
                OpmlFeed("https://feeds.example.com/daily", "Daily Example", "https://www.example.com/daily"),
                OpmlFeed("https://premium.example.com/feed/abc?token=s3cr3t&user=7", "Private Feed", null),
                OpmlFeed("http://gadgets.example.org/rss", "Gadget Talk & More", null),
                // A javascript: site link is dropped; the feed itself is fine.
                OpmlFeed("https://nocat.example.net/feed.xml", "No category", null),
            ),
            result.feeds,
        )
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `bad URLs and duplicates are skipped with a reason`() {
        val result = parse(Fixtures.bytes("corpus/opml/bad-urls.opml")).success()
        assertEquals(
            listOf("https://good.example.com/feed", "http://second.example.org/rss?a=1&b=2"),
            result.feeds.map { it.feedUrl },
        )
        assertEquals(
            listOf(
                OpmlSkip("Duplicate of good", OpmlSkipReason.Duplicate),
                OpmlSkip("Duplicate after trimming", OpmlSkipReason.Duplicate),
                OpmlSkip("Script", OpmlSkipReason.InvalidUrl(UrlProblem.DisallowedScheme)),
                OpmlSkip("Local file", OpmlSkipReason.InvalidUrl(UrlProblem.DisallowedScheme)),
                OpmlSkip("FTP", OpmlSkipReason.InvalidUrl(UrlProblem.DisallowedScheme)),
                OpmlSkip("Data", OpmlSkipReason.InvalidUrl(UrlProblem.DisallowedScheme)),
                OpmlSkip("Intent", OpmlSkipReason.InvalidUrl(UrlProblem.DisallowedScheme)),
                OpmlSkip("Empty", OpmlSkipReason.MissingUrl),
                OpmlSkip("Ambiguous host", OpmlSkipReason.InvalidUrl(UrlProblem.AmbiguousNumericHost)),
            ),
            result.skipped,
        )
        assertEquals("Bad URLs & duplicates", result.title)
    }

    @Test
    fun `hostile OPML files are refused`() {
        assertEquals(ParseProblem.EntityDeclaration, parse(Fixtures.bytes("corpus/opml/entity-expansion-bomb.opml")).problem())
        assertEquals(ParseProblem.EntityDeclaration, parse(Fixtures.bytes("corpus/opml/external-entity.opml")).problem())
        assertEquals(ParseProblem.HtmlPage, parse(Fixtures.bytes("corpus/opml/html-instead-of-opml.html")).problem())
        assertEquals(ParseProblem.NotAFeed("rss"), parse("<rss><channel/></rss>").problem())
    }

    @Test
    fun `deep nesting, too many outlines and oversized files are refused`() {
        val deep = "<opml><body>" + "<outline text=\"x\">".repeat(70) + "</outline>".repeat(70) + "</body></opml>"
        assertEquals(ParseProblem.TooDeep(64), parse(deep).problem())

        val many = "<opml><body>" + (1..11).joinToString("") { "<outline xmlUrl=\"https://f$it.example.com/\"/>" } + "</body></opml>"
        assertEquals(ParseProblem.TooManyItems(10), parse(many.toByteArray(), XmlLimits(maxItems = 10)).problem())

        assertEquals(ParseProblem.TooLarge(100), parse(many.toByteArray(), XmlLimits(maxBytes = 100)).problem())
    }

    @Test
    fun `OPML 1 attribute spellings and missing titles`() {
        val opml = "<opml version=\"1.0\"><body>" +
            "<outline type=\"rss\" title=\"Only title\" xmlurl=\"https://a.example.com/\"/>" +
            "<outline xmlUrl=\"https://b.example.com/\"/>" +
            "<outline text=\"  spaced   text  \" xmlUrl=\"https://c.example.com/\" htmlurl=\"https://c.example.com/site\"/>" +
            "</body></opml>"
        assertEquals(
            listOf(
                OpmlFeed("https://a.example.com/", "Only title", null),
                OpmlFeed("https://b.example.com/", null, null),
                OpmlFeed("https://c.example.com/", "spaced text", "https://c.example.com/site"),
            ),
            parse(opml).success().feeds,
        )
    }

    @Test
    fun `export then import gives the same subscriptions`() {
        val feeds = listOf(
            OpmlFeed("https://feeds.example.com/daily", "Daily Example", "https://www.example.com/daily"),
            OpmlFeed("https://premium.example.com/feed/abc?token=s3cr3t&user=7", "Ampersand & <angle> \"quotes\" 'apostrophes'", null),
            OpmlFeed("http://gadgets.example.org/rss", "Café – 🎙 日本", null),
            OpmlFeed("https://untitled.example.net/feed", null, null),
        )
        val exported = OpmlWriter.write(feeds, createdAt = Instant.parse("2026-11-08T12:00:00Z"))
        val result = parse(exported).success()
        assertEquals(feeds, result.feeds)
        assertEquals(OpmlWriter.DEFAULT_TITLE, result.title)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `export is well-formed OPML 2_0`() {
        val exported = OpmlWriter.write(
            listOf(OpmlFeed("https://a.example.com/", "A", null)),
            createdAt = Instant.parse("2026-11-08T12:00:00Z"),
        )
        assertTrue(exported.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">"))
        assertTrue(exported, exported.contains("<dateCreated>Sun, 8 Nov 2026 12:00:00 GMT</dateCreated>"))
        assertTrue(exported, exported.contains("<outline type=\"rss\" text=\"A\" title=\"A\" xmlUrl=\"https://a.example.com/\"/>"))
    }

    @Test
    fun `export drops characters that XML cannot carry`() {
        val exported = OpmlWriter.write(listOf(OpmlFeed("https://a.example.com/", "bad\u0001title$NONCHARACTER", null)))
        assertFalse(exported.contains('\u0001'))
        assertEquals("badtitle", parse(exported).success().feeds.single().title)
    }

    @Test
    fun `an empty export is still valid`() {
        val result = parse(OpmlWriter.write(emptyList())).success()
        assertTrue(result.feeds.isEmpty())
    }

    @Test
    fun `a whole exported library round-trips`() {
        val feeds = (1..2_000).map { OpmlFeed("https://feeds$it.example.com/rss?id=$it&k=v", "Show $it", "https://site$it.example.com/") }
        assertEquals(feeds, parse(OpmlWriter.write(feeds)).success().feeds)
    }

    @Test
    fun `feed URLs never show in toString`() {
        val feed = OpmlFeed("https://premium.example.com/feed/abc?token=s3cr3t", "Private", null)
        assertFalse(feed.toString().contains("s3cr3t"))
    }
}
