/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.notes

import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import us.dikarya.podplayer.core.feed.FakeTicker
import us.dikarya.podplayer.core.feed.feed
import us.dikarya.podplayer.core.feed.parseFixture
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.testing.Fixtures

/** SPEC §5: allowlist sanitizing to a neutral rich-text model; http, https and mailto links only. */
class ShowNotesTest {
    private fun rich(html: String?, durationMs: Long? = null): RichText =
        when (val result = ShowNotes.sanitize(html, durationMs)) {
            is NotesResult.Ok -> result.text
            is NotesResult.Rejected -> fail("Rejected: $result") as Nothing
        }

    private fun RichText.styled(style: (RichStyle) -> Boolean): List<String> =
        spans.filter { style(it.style) }.map { text.substring(it.start, it.end) }

    private fun RichText.links(): List<String> = spans.mapNotNull { (it.style as? RichStyle.Link)?.url }

    private fun RichText.timestamps(): List<Long> = spans.mapNotNull { (it.style as? RichStyle.Timestamp)?.positionMs }

    private val hostile by lazy { rich(Fixtures.text("corpus/notes/hostile-notes.html")) }

    @Test
    fun `scripts, styles, frames, forms, media and embedded documents are dropped with their content`() {
        val text = hostile.text
        for (gone in listOf(
            "document.location", "cookie", "color: red", "noscript text", "iframe fallback", "Password",
            "textarea text", "option text", "Log in", "object fallback", "video fallback", "audio fallback",
            "svg text", "alert", "math text", "template text", "pixel", "evil.example.test", "tracker",
        )) {
            assertFalse("'$gone' leaked into: $text", text.contains(gone))
        }
    }

    @Test
    fun `ordinary text from allowed and unknown harmless elements is kept`() {
        val text = hostile.text
        for (kept in listOf(
            "Episode notes", "Welcome!", "Styled paragraph", "Link one", "JS link", "Intent link", "Data link",
            "Encoded JS link", "Newline JS link", "Mail us", "A quote with a cleartext link.", "Table cell one",
            "Table cell two", "Span text", "Font text",
        )) {
            assertTrue("'$kept' missing from: $text", text.contains(kept))
        }
    }

    @Test
    fun `only http, https and mailto links survive`() {
        assertEquals(
            listOf("https://example.org/one", "mailto:host@example.org?subject=Hi", "http://example.com/quote"),
            hostile.links(),
        )
        assertTrue(hostile.links().all { UrlPolicy.isAllowedLink(it) })
    }

    @Test
    fun `links show where they go`() {
        val destinations = hostile.spans.mapNotNull { (it.style as? RichStyle.Link)?.destination }
        assertEquals(listOf("example.org", "example.org", "example.com"), destinations)
        // The part before @ in an authority is not the destination.
        val phishing = rich("<a href=\"https://bank.example.com@evil.example.test/login\">bank</a>")
        assertEquals("evil.example.test", (phishing.spans.single().style as RichStyle.Link).destination)
    }

    @Test
    fun `bold, italic, headings, quotes and list items`() {
        assertEquals(listOf("Episode notes", "Bold", "also bold"), hostile.styled { it == RichStyle.Bold })
        assertEquals(listOf("italic", "also italic"), hostile.styled { it == RichStyle.Italic })
        assertEquals(listOf("A quote with a cleartext link."), hostile.styled { it == RichStyle.Quote })
        assertEquals(7, hostile.spans.count { it.style is RichStyle.ListItem })
    }

    @Test
    fun `timestamps become seek spans, other numbers do not`() {
        assertEquals(listOf(0L, 330_000L, 4_500_000L), hostile.timestamps())
    }

    @Test
    fun `timestamps past the end of the episode are left as text`() {
        assertEquals(listOf(60_000L), rich("<p>01:00 and 59:00</p>", durationMs = 30 * 60_000L).timestamps())
    }

    @Test
    fun `a timestamp inside link text is not a second link`() {
        val notes = rich("<p><a href=\"https://example.org/\">at 12:34</a> and 05:00</p>")
        assertEquals(listOf(300_000L), notes.timestamps())
    }

    @Test
    fun `every span lies inside the text and no link has a disallowed scheme`() {
        val samples = listOf(
            hostile,
            rich(parseFixture("corpus/feeds/javascript-links.xml").feed().episodes.single().notes),
            rich(parseFixture("samples/feeds/hosted-platform.xml").feed().episodes[0].notes),
        )
        for (notes in samples) {
            for (span in notes.spans) {
                assertTrue(span.toString(), span.start in 0..span.end && span.end <= notes.text.length)
                val style = span.style
                if (style is RichStyle.Link) assertTrue(style.url, UrlPolicy.isAllowedLink(style.url))
            }
        }
    }

    @Test
    fun `the javascript-links fixture keeps only its safe links`() {
        val notes = rich(parseFixture("corpus/feeds/javascript-links.xml").feed().episodes.single().notes)
        assertEquals(listOf("https://example.org/show", "mailto:host@example.org"), notes.links())
        assertFalse(notes.text.contains("hunter2"))
        assertFalse(notes.text.contains("iframe text"))
        assertFalse(notes.text.contains("svg text"))
        assertTrue(notes.text.contains("Paragraph with handlers"))
    }

    @Test
    fun `paragraphs, line breaks and whitespace`() {
        val notes = rich("  <p>One\n   two</p><p>Three<br>four</p>  <div>Five</div>")
        assertEquals("One two\n\nThree\nfour\n\nFive", notes.text)
    }

    @Test
    fun `lists get markers and nesting levels`() {
        val notes = rich("<ul><li>a</li><li>b<ol><li>c</li><li>d</li></ol></li></ul><p>after</p>")
        assertEquals("• a\n• b\n1. c\n2. d\n\nafter", notes.text)
        val levels = notes.spans.mapNotNull { (it.style as? RichStyle.ListItem)?.level }
        assertEquals(listOf(0, 0, 1, 1), levels)
    }

    @Test
    fun `h1 to h4 are bold, other headings are plain`() {
        val notes = rich("<h1>A</h1><h4>B</h4><h5>C</h5>")
        assertEquals("A\n\nB\n\nC", notes.text)
        assertEquals(listOf("A", "B"), notes.styled { it == RichStyle.Bold })
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Fish & chips © <3", rich("<p>Fish &amp; chips &copy; &lt;3</p>").text)
    }

    @Test
    fun `entities are decoded in notes without markup too`() {
        // Seen on the owner's phone: ABC's 20/20 description has no tags but ends "subscribe now.&nbsp; Terms apply."
        assertEquals("subscribe now.  Terms apply. Q&A", rich("subscribe now.&nbsp; Terms apply. Q&amp;A").text)
        // A bare ampersand is just an ampersand.
        assertEquals("AT&T and R&D, 5 & 6", rich("AT&T and R&D, 5 & 6").text)
    }

    @Test
    fun `plain text notes keep their lines and get links and timestamps`() {
        val notes = rich("Line one.\nLine two at 12:34, see https://example.org/page.\n\nNew paragraph (https://example.org/x).")
        assertEquals("Line one.\nLine two at 12:34, see https://example.org/page.\n\nNew paragraph (https://example.org/x).", notes.text)
        assertEquals(listOf("https://example.org/page", "https://example.org/x"), notes.links())
        assertEquals(listOf(754_000L), notes.timestamps())
    }

    @Test
    fun `bare URLs in HTML text are linked, but not inside existing links`() {
        val notes = rich("<p>Go to https://example.org/a, or <a href=\"https://example.org/b\">https://example.org/b</a></p>")
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), notes.links())
    }

    @Test
    fun `links without an href or with an empty one are plain text`() {
        val notes = rich("<a>no href</a> <a href=\"\">empty</a> <a href=\"   \">blank</a>")
        assertEquals("no href empty blank", notes.text)
        assertTrue(notes.spans.isEmpty())
    }

    @Test
    fun `null and blank notes are empty`() {
        assertEquals(RichText.EMPTY, rich(null))
        assertEquals(RichText.EMPTY, rich("   \n "))
        assertEquals(RichText.EMPTY, rich("<script>x</script>"))
    }

    @Test
    fun `deep nesting does not overflow`() {
        val deep = "<b>".repeat(20_000) + "deep" + "</b>".repeat(20_000)
        assertTrue(rich(deep).text.contains("deep"))
    }

    @Test
    fun `limits reject oversized, huge and slow notes`() {
        val limits = NotesLimits(maxInputChars = 1000, maxElements = 100, maxDuration = 2.seconds)
        assertEquals(
            NotesResult.Rejected(NotesProblem.TooLarge),
            ShowNotes.sanitize("a".repeat(1001), limits = limits),
        )
        assertEquals(
            NotesResult.Rejected(NotesProblem.TooManyElements),
            ShowNotes.sanitize("<i>x</i>".repeat(101), limits = limits),
        )
        assertEquals(
            NotesResult.Rejected(NotesProblem.TimeLimit),
            ShowNotes.sanitize("<p>x</p>".repeat(50), limits = limits, ticker = FakeTicker(step = 3.seconds.inWholeNanoseconds)),
        )
    }

    @Test
    fun `notes without markup are held to the time limit too`() {
        val limits = NotesLimits(maxDuration = 2.seconds)
        assertEquals(
            NotesResult.Rejected(NotesProblem.TimeLimit),
            ShowNotes.sanitize("line one\nline two 12:34", limits = limits, ticker = FakeTicker(step = 3.seconds.inWholeNanoseconds)),
        )
        // Within the budget the same notes are fine.
        assertTrue(ShowNotes.sanitize("line one\nline two 12:34", limits = limits, ticker = FakeTicker(step = 1)) is NotesResult.Ok)
    }

    @Test
    fun `the default input limit is generous`() {
        assertTrue(ShowNotes.sanitize("<p>" + "word ".repeat(100_000) + "</p>") is NotesResult.Ok)
    }

    @Test
    fun `a bare URL followed by a long run of brackets is trimmed in linear time`() {
        // The trimming of unbalanced brackets used to recount them for every bracket dropped, so 200,000
        // of them took minutes, with no time check in between: a hostile note could hang the notes screen.
        val html = "see https://example.com/a" + ")".repeat(200_000) + " and https://example.com/b]]]."
        val start = System.nanoTime()
        val text = rich(html)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $millis ms", millis < 2_000)
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), text.links())
    }

    @Test
    fun `brackets a URL opened are kept, brackets it didn't open are trimmed`() {
        assertEquals(listOf("https://example.com/a_(b)"), rich("(see https://example.com/a_(b))").links())
        assertEquals(listOf("https://example.com/x"), rich("[https://example.com/x]").links())
        assertEquals(listOf("https://example.com/p?q=1"), rich("Visit https://example.com/p?q=1.").links())
    }
}
