/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.notes

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.feed.notes.NotesResult
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.core.feed.notes.ShowNotes
import us.dikarya.podplayer.ui.theme.DarkTokens

/** SPEC §5: sanitized notes become native text; links show their destination first; timestamps seek. */
class NotesTextTest {
    private val links = mutableListOf<RichStyle.Link>()
    private val seeks = mutableListOf<Long>()

    private fun convert(html: String, durationMs: Long? = null, timestamps: Boolean = true) =
        (ShowNotes.sanitize(html, durationMs) as NotesResult.Ok).text.toAnnotatedString(
            colors = DarkTokens,
            onLink = { links += it },
            onTimestamp = if (timestamps) ({ seeks += it }) else null,
        )

    @Test
    fun boldAndItalicBecomeSpanStyles() {
        val text = convert("<p><b>Big</b> and <em>slanted</em></p>")
        assertEquals("Big and slanted", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Big", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("slanted", text.text.substring(italic.start, italic.end))
    }

    @Test
    fun aLinkDoesNotOpenAnythingByItselfItReportsItsDestination() {
        val text = convert("""<p>See <a href="https://bank.example@evil.example/login">my bank</a></p>""")
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("my bank", text.text.substring(link.start, link.end))
        val clickable = link.item as LinkAnnotation.Clickable
        clickable.linkInteractionListener!!.onClick(clickable)
        val reported = links.single()
        assertEquals("the domain shown is the real one", "evil.example", reported.destination)
        assertEquals("https://bank.example@evil.example/login", reported.url)
    }

    @Test
    fun scriptLinksNeverBecomeLinks() {
        val text = convert("""<p><a href="javascript:alert(1)">tap</a> <a href="intent://x#Intent;end">here</a></p>""")
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        assertEquals("tap here", text.text)
    }

    @Test
    fun aTimestampSeeksToItsPosition() {
        val text = convert("<p>Intro 0:30, interview at 12:34</p>", durationMs = 3_600_000)
        val stamps = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("0:30", "12:34"), stamps.map { text.text.substring(it.start, it.end) })
        stamps.forEach { (it.item as LinkAnnotation.Clickable).let { c -> c.linkInteractionListener!!.onClick(c) } }
        assertEquals(listOf(30_000L, 754_000L), seeks)
    }

    @Test
    fun withoutAnEpisodeToSeekTimestampsStayPlainText() {
        // A show's description has times in it too, but no episode to seek in.
        val text = convert("<p>New every week at 12:30</p>", timestamps = false)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        assertNull(seeks.firstOrNull())
    }

    @Test
    fun listItemsAndQuotesKeepTheirShape() {
        val text = convert("<ul><li>One</li><li>Two<ul><li>Inner</li></ul></li></ul><blockquote>Said</blockquote>")
        assertTrue(text.text.contains("• One"))
        val indents = text.paragraphStyles.sortedBy { it.start }.map { it.item.textIndent?.restLine?.value ?: 0f }
        assertEquals(3, indents.size)
        assertTrue("the inner item is indented further", indents[2] > indents[0])
        val quote = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("Said", text.text.substring(quote.start, quote.end))
    }
}
