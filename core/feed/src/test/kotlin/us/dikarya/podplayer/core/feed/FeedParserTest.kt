/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §4 "Fields to keep", on synthetic feeds that mimic real publisher patterns. */
class FeedParserTest {
    @Test
    fun `hosted-platform feed - channel fields`() {
        val feed = parseFixture("samples/feeds/hosted-platform.xml").feed()
        assertEquals(FeedFormat.Rss, feed.format)
        assertEquals("The Example Hour", feed.title)
        assertEquals("https://www.example.com/example-hour", feed.link)
        assertEquals("<p>A weekly show about <strong>examples</strong>.</p><p>New episodes every Monday.</p>", feed.description)
        assertEquals("Example Media", feed.author)
        // itunes:image comes before image/url.
        assertEquals("https://img.example.com/cover-3000.jpg", feed.imageUrl)
        assertEquals(listOf("Technology", "Society & Culture", "Documentary"), feed.categories)
        assertEquals(4, feed.episodes.size)
    }

    @Test
    fun `hosted-platform feed - a full item`() {
        val ep = parseFixture("samples/feeds/hosted-platform.xml").feed().episodes[0]
        assertEquals("a1b2c3d4-0042-4000-8000-000000000042", ep.guid)
        assertEquals("Ep. 42: The Answer", ep.title)
        assertEquals(millis("2026-11-02T13:00:00Z"), ep.publishedAt)
        assertEquals(3_723_000L, ep.durationMs)
        assertEquals(3, ep.season)
        assertEquals(42, ep.episode)
        assertEquals("https://img.example.com/ep42.jpg", ep.imageUrl)
        assertEquals("https://www.example.com/example-hour/42", ep.link)
        val enclosure = ep.enclosure!!
        assertEquals(
            "https://tracker.example.net/redirect.mp3/media.example.com/example-hour/42.mp3?awCollectionId=1&awEpisodeId=42",
            enclosure.url,
        )
        assertEquals("audio/mpeg", enclosure.type)
        assertEquals(59_576_320L, enclosure.length)
        // content:encoded is preferred over description.
        assertTrue(ep.notes!!.startsWith("<p>We talk about the answer.</p>"))
    }

    @Test
    fun `hosted-platform feed - deviations in other items`() {
        val episodes = parseFixture("samples/feeds/hosted-platform.xml").feed().episodes
        val ep41 = episodes[1]
        assertEquals(2_710_000L, ep41.durationMs)
        assertNull("length 0 means unknown", ep41.enclosure!!.length)
        assertEquals("audio/x-m4a", ep41.enclosure!!.type)
        assertEquals(
            "Plain text notes.\nSecond line with a link https://www.example.com/41 and a time 05:00.",
            ep41.notes,
        )
        val ep40 = episodes[2]
        assertNull(ep40.enclosure!!.length)
        assertEquals("application/octet-stream", ep40.enclosure!!.type)
        assertEquals("Only an iTunes summary here.", ep40.notes)
        val post = episodes[3]
        assertNull(post.enclosure)
        assertEquals("Not an episode.", post.notes)
    }

    @Test
    fun `Podcasting 2_0 channel tags`() {
        val result = parseFixture("samples/feeds/podcasting20.xml").success()
        val feed = result.feed
        assertEquals("917393e3-1b1e-5cef-ace4-edaa54e1f810", feed.podcastGuid)
        assertEquals(listOf(Funding("https://donate.example.org/namespace", "Support the show")), feed.funding)
        assertEquals(
            listOf(
                Person("Alice Example", role = "host", group = null, imageUrl = "https://img.example.org/alice.jpg", link = "https://alice.example.org/"),
                Person("Bob Example", role = "guest", group = "writing", imageUrl = null, link = null),
            ),
            feed.persons,
        )
        assertEquals(License("cc-by-4.0", "https://creativecommons.org/licenses/by/4.0/"), feed.license)
        assertEquals("https://img.example.org/fallback.png", feed.imageUrl)
        assertEquals(listOf("Technology"), feed.categories)
        assertEquals("Namespace Collective", feed.author)
    }

    @Test
    fun `Podcasting 2_0 item tags`() {
        val ep = parseFixture("samples/feeds/podcasting20.xml").feed().episodes.single()
        assertEquals(2, ep.season)
        assertEquals(7, ep.episode)
        assertEquals(2_400_500L, ep.durationMs)
        assertEquals(Chapters("https://media.example.org/ns20-1.chapters.json", "application/json+chapters"), ep.chapters)
        assertEquals(
            listOf(
                Transcript("https://media.example.org/ns20-1.vtt", "text/vtt", language = "en", rel = "captions"),
                Transcript("https://media.example.org/ns20-1.srt", "application/x-subrip", language = null, rel = null),
            ),
            ep.transcripts,
        )
        assertEquals(listOf(Soundbite(startMs = 73_000, durationMs = 60_500, title = "The best minute")), ep.soundbites)
        val alt = ep.alternateEnclosures.single()
        assertEquals("audio/opus", alt.type)
        assertEquals(12_000_000L, alt.length)
        assertEquals(64_000L, alt.bitrate)
        assertEquals("Opus", alt.title)
        assertEquals("en", alt.language)
        assertFalse(alt.isDefault)
        // ipfs: is not http(s), so only one source is kept.
        assertEquals(listOf("https://media.example.org/ns20-1.opus"), alt.sources)
    }

    @Test
    fun `Atom feed basics`() {
        val feed = parseFixture("samples/feeds/atom.xml").feed()
        assertEquals(FeedFormat.Atom, feed.format)
        assertEquals("Atomic Audio", feed.title)
        assertEquals("https://atomic.example.com/", feed.link)
        assertEquals("<p>An Atom podcast.</p>", feed.description)
        assertEquals("Atomic Authors", feed.author)
        assertEquals("https://atomic.example.com/logo.png", feed.imageUrl)
        assertEquals(listOf("Science"), feed.categories)

        val (first, second) = feed.episodes
        assertEquals("urn:uuid:1225c695-cfb8-4ebb-aaaa-80da344efa6a", first.guid)
        assertEquals("Split the atom", first.title)
        assertEquals(millis("2026-11-01T16:30:02Z"), first.publishedAt)
        assertEquals(ParsedEnclosure("https://atomic.example.com/2.mp3", "audio/mpeg", 1337), first.enclosure)
        assertEquals("https://atomic.example.com/2", first.link)
        assertEquals(1_500_000L, first.durationMs)
        assertEquals("https://atomic.example.com/2.jpg", first.imageUrl)
        // XHTML content is kept as markup for the sanitizer.
        assertTrue(first.notes, first.notes!!.contains("<b>content</b>"))
        assertTrue(first.notes, first.notes!!.contains("href=\"https://atomic.example.com/ref\""))

        assertEquals("Only updated", second.title)
        assertEquals(millis("2026-10-25T08:00:00.123Z"), second.publishedAt)
        assertEquals(ParsedEnclosure("https://atomic.example.com/1.m4a", "audio/mp4", null), second.enclosure)
        assertEquals("<p>Escaped HTML content.</p>", second.notes)
    }

    @Test
    fun `the audio enclosure is preferred when an item has several`() {
        val xml = rss(
            items = "<item><title>T</title>" +
                "<enclosure url=\"https://img.example.com/cover.jpg\" type=\"image/jpeg\" length=\"5\"/>" +
                "<enclosure url=\"https://media.example.com/ep.mp3\" type=\"audio/mpeg\" length=\"9\"/></item>",
        )
        assertEquals("https://media.example.com/ep.mp3", parseFeed(xml).feed().episodes.single().enclosure?.url)
    }

    @Test
    fun `titles are trimmed and whitespace collapsed, notes are only trimmed`() {
        val xml = rss(
            channel = "<title>\n  Many   spaces\t here \n</title>",
            items = "<item><title> A\n B </title><description>\n line 1\n line 2 \n</description></item>",
        )
        val feed = parseFeed(xml).feed()
        assertEquals("Many spaces here", feed.title)
        assertEquals("A B", feed.episodes.single().title)
        assertEquals("line 1\n line 2", feed.episodes.single().notes)
    }

    @Test
    fun `blank fields become null`() {
        val xml = rss(channel = "<title>  </title><link></link>", items = "<item><guid> </guid><title/></item>")
        val feed = parseFeed(xml).feed()
        assertNull(feed.title)
        assertNull(feed.link)
        assertNull(feed.episodes.single().guid)
        assertNull(feed.episodes.single().title)
    }

    @Test
    fun `long fields are capped and the cap is reported`() {
        val longTitle = "t".repeat(5_000)
        val categories = (1..500).joinToString("") { "<category>c$it</category>" }
        val result = parseFeed(rss(channel = "<title>$longTitle</title>$categories")).success()
        assertEquals(FieldLimits.TITLE, result.feed.title!!.length)
        assertEquals(FieldLimits.LIST, result.feed.categories.size)
        assertTrue(result.warnings.filterIsInstance<FeedWarning.FieldsTruncated>().single().count >= 2)
    }

    @Test
    fun `season and episode numbers must be sensible`() {
        val items = listOf("-1", "0", "abc", "1.5", "99999999999", " 12 ").joinToString("") {
            "<item><itunes:season>$it</itunes:season><itunes:episode>$it</itunes:episode></item>"
        }
        val episodes = parseFeed(rss(items = items)).feed().episodes
        assertEquals(listOf(null, 0, null, null, null, 12), episodes.map { it.episode })
        assertEquals(listOf(null, 0, null, null, null, 12), episodes.map { it.season })
    }

    @Test
    fun `item-level unknown elements and namespaces are skipped`() {
        val xml = rss(
            items = "<item><title>T</title><media:content xmlns:media=\"http://search.yahoo.com/mrss/\" url=\"x\">" +
                "<media:title>inner</media:title></media:content><unknown><deep>x</deep></unknown></item>",
        )
        assertEquals("T", parseFeed(xml).feed().episodes.single().title)
    }

    @Test
    fun `Atom feeds with a default namespace and RSS feeds with Atom links both work`() {
        val xml = rss(
            channel = "<atom:link xmlns:atom=\"http://www.w3.org/2005/Atom\" rel=\"self\" href=\"https://x.example.com/feed\"/>" +
                "<link>https://x.example.com/</link><title>T</title>",
        )
        assertEquals("https://x.example.com/", parseFeed(xml).feed().link)
    }

    @Test
    fun `dc date is a fallback for pubDate`() {
        val xml = rss(
            items = "<item><dc:date xmlns:dc=\"http://purl.org/dc/elements/1.1/\">2026-01-02T03:04:05Z</dc:date></item>",
        )
        assertEquals(millis("2026-01-02T03:04:05Z"), parseFeed(xml).feed().episodes.single().publishedAt)
    }

    @Test
    fun `the iTunes namespace is matched without regard to case`() {
        val xml = "<rss xmlns:itunes=\"http://www.itunes.com/DTDs/Podcast-1.0.dtd\"><channel>" +
            "<itunes:author>Case</itunes:author></channel></rss>"
        assertEquals("Case", parseFeed(xml).feed().author)
    }

    @Test
    fun `toString never shows enclosure query strings`() {
        val feed = parseFixture("samples/feeds/hosted-platform.xml").feed()
        assertFalse(feed.toString().contains("awEpisodeId"))
    }

    @Test
    fun `random mutations of a real-looking feed never throw`() {
        val base = rss(
            channel = "<title>Fuzz &amp; co</title><itunes:image href=\"https://i.example.com/a.jpg\"/>",
            items = "<item><title>A</title><guid>g</guid><pubDate>Mon, 05 Oct 2026 13:00:00 GMT</pubDate>" +
                "<enclosure url=\"https://m.example.com/a.mp3\" length=\"10\" type=\"audio/mpeg\"/>" +
                "<itunes:duration>1:00</itunes:duration><podcast:soundbite startTime=\"1\" duration=\"2\">x</podcast:soundbite>" +
                "<description><![CDATA[<p>hi</p>]]></description></item>",
        ).toByteArray()
        val random = java.util.Random(42)
        val specials = "<>&;\"'/=![]?-:#x0\u0000ÿ".toByteArray(Charsets.ISO_8859_1)
        repeat(3_000) {
            val bytes = base.copyOf()
            repeat(1 + random.nextInt(8)) {
                val at = random.nextInt(bytes.size)
                bytes[at] = if (random.nextBoolean()) specials[random.nextInt(specials.size)] else random.nextInt(256).toByte()
            }
            parseFeed(bytes)
        }
    }
}
