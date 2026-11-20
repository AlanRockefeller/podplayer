/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.xml

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.feed.BOM
import us.dikarya.podplayer.core.feed.REPLACEMENT
import us.dikarya.podplayer.core.testing.SyntheticFeeds

/** SPEC §4: BOM, then XML declaration, then HTTP header, then UTF-8 with replacement. */
class EncodingDetectorTest {
    private val latin1 = Charsets.ISO_8859_1
    private val windows1252 = Charset.forName("windows-1252")

    @Test
    fun `byte order marks win over everything`() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            // The declaration and the header both lie; the BOM decides.
            val bytes = SyntheticFeeds.encoded(SyntheticFeeds.accented("ISO-8859-1"), charset, bom = true)
            val decoded = EncodingDetector.decode(bytes, "text/xml; charset=windows-1252")
            assertEquals(EncodingSource.ByteOrderMark, decoded.source)
            assertEquals(charset, decoded.charset)
            assertTrue(decoded.text.startsWith("<?xml"))
            assertTrue(decoded.text, "Café crème über" in decoded.text)
        }
    }

    @Test
    fun `UTF-16 without a byte order mark is recognised from the first characters`() {
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = SyntheticFeeds.encoded(SyntheticFeeds.accented("UTF-16"), charset, bom = false)
            val decoded = EncodingDetector.decode(bytes, null)
            assertEquals(charset, decoded.charset)
            assertEquals(EncodingSource.Sniffed, decoded.source)
            assertTrue("über" in decoded.text)
        }
    }

    @Test
    fun `the XML declaration comes before the HTTP header`() {
        val bytes = SyntheticFeeds.accented("windows-1252").toByteArray(windows1252)
        val decoded = EncodingDetector.decode(bytes, "application/rss+xml; charset=UTF-8")
        assertEquals(EncodingSource.XmlDeclaration, decoded.source)
        assertEquals(windows1252, decoded.charset)
        assertTrue("Café" in decoded.text)
    }

    @Test
    fun `ISO-8859-1 is read as windows-1252, as browsers do, so curly quotes survive`() {
        val bytes = "<?xml version=\"1.0\" encoding=\"iso-8859-1\"?><rss>“hi” –</rss>".toByteArray(windows1252)
        val decoded = EncodingDetector.decode(bytes, null)
        assertEquals(windows1252, decoded.charset)
        assertTrue(decoded.text, "“hi” –" in decoded.text)
    }

    @Test
    fun `the HTTP header is used when there is no declaration`() {
        val bytes = SyntheticFeeds.accented(null).toByteArray(latin1)
        for (header in listOf("text/xml; charset=ISO-8859-1", "text/xml;charset=\"iso-8859-1\"", "TEXT/XML; Charset=Latin1")) {
            val decoded = EncodingDetector.decode(bytes, header)
            assertEquals(header, EncodingSource.HttpHeader, decoded.source)
            assertTrue(header, "Café" in decoded.text)
        }
    }

    @Test
    fun `no declaration and no header means UTF-8`() {
        val bytes = SyntheticFeeds.accented(null).toByteArray(Charsets.UTF_8)
        val decoded = EncodingDetector.decode(bytes, "text/xml")
        assertEquals(EncodingSource.Default, decoded.source)
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertTrue("Café" in decoded.text)
    }

    @Test
    fun `invalid UTF-8 is decoded with replacement characters instead of failing`() {
        val bytes = SyntheticFeeds.accented("UTF-8").toByteArray(latin1)
        val decoded = EncodingDetector.decode(bytes, null)
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertTrue(decoded.text, "Caf$REPLACEMENT" in decoded.text)
    }

    @Test
    fun `a single-byte declaration on bytes that are really UTF-8 is overruled`() {
        // The "wrong declared encoding" case: the publisher's template says Latin-1, the text is UTF-8.
        val bytes = SyntheticFeeds.accented("ISO-8859-1").toByteArray(Charsets.UTF_8)
        val decoded = EncodingDetector.decode(bytes, "text/xml; charset=ISO-8859-1")
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertEquals(EncodingSource.Sniffed, decoded.source)
        assertTrue(decoded.text, "Café crème über" in decoded.text)
    }

    @Test
    fun `plain ASCII with a single-byte declaration keeps the declaration`() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss/>".toByteArray(latin1)
        assertEquals(EncodingSource.XmlDeclaration, EncodingDetector.decode(bytes, null).source)
    }

    @Test
    fun `unknown, unsupported or absurd charset names are ignored`() {
        val bytes = SyntheticFeeds.accented(null).toByteArray(Charsets.UTF_8)
        for (name in listOf("x-no-such-charset", "../../etc", "a".repeat(1000), "")) {
            val withDecl = "<?xml version=\"1.0\" encoding=\"$name\"?><rss>é</rss>".toByteArray(Charsets.UTF_8)
            val decoded = EncodingDetector.decode(withDecl, "text/xml; charset=$name")
            assertEquals(name, Charsets.UTF_8, decoded.charset)
            assertTrue("é" in decoded.text)
        }
        assertEquals(Charsets.UTF_8, EncodingDetector.decode(bytes, "text/xml; charset=").charset)
    }

    @Test
    fun `a UTF-16 declaration on single-byte text is a lie and is ignored`() {
        val bytes = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><rss>café</rss>".toByteArray(Charsets.UTF_8)
        val decoded = EncodingDetector.decode(bytes, null)
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertTrue("café" in decoded.text)
    }

    @Test
    fun `the byte order mark is not part of the text`() {
        val bytes = SyntheticFeeds.encoded("<rss/>", Charsets.UTF_8, bom = true)
        val decoded = EncodingDetector.decode(bytes, null)
        assertEquals("<rss/>", decoded.text)
        assertFalse(decoded.text.startsWith(BOM))
    }

    @Test
    fun `empty input decodes to empty text`() {
        assertEquals("", EncodingDetector.decode(ByteArray(0), null).text)
    }
}
