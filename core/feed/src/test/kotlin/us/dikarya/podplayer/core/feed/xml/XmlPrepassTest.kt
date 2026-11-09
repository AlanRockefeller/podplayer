/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.xml

import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import us.dikarya.podplayer.core.feed.BOM
import us.dikarya.podplayer.core.feed.FakeTicker
import us.dikarya.podplayer.core.feed.NONCHARACTER
import us.dikarya.podplayer.core.feed.ParseProblem

/** SPEC §4 pre-pass: illegal characters, bare ampersands, DOCTYPE rules, HTML sniffing. */
class XmlPrepassTest {
    private fun clean(text: String, limits: XmlLimits = XmlLimits(), ticker: Ticker = Ticker.SYSTEM): PrepassResult =
        XmlPrepass.clean(text, limits, ticker)

    private fun ready(text: String): PrepassResult.Ready =
        clean(text) as? PrepassResult.Ready ?: fail("Expected Ready, got ${clean(text)}") as Nothing

    private fun rejected(text: String): ParseProblem =
        (clean(text) as? PrepassResult.Rejected ?: fail("Expected Rejected, got ${clean(text)}") as Nothing).problem

    @Test
    fun `bare ampersands are escaped in text and attributes`() {
        val result = ready("<rss a=\"x?a=1&b=2\">Tom & Jerry &</rss>")
        assertEquals("<rss a=\"x?a=1&amp;b=2\">Tom &amp; Jerry &amp;</rss>", result.xml)
        assertEquals(3, result.repairs.bareAmpersands)
    }

    @Test
    fun `valid references are kept`() {
        val xml = "<rss>&amp; &lt; &gt; &quot; &apos; &#38; &#x26; &#X26; &#128512;</rss>"
        val result = ready(xml)
        assertEquals(xml, result.xml)
        assertEquals(Repairs.NONE, result.repairs)
    }

    @Test
    fun `HTML named entities become character references and unknown names are escaped`() {
        val result = ready("<rss>&nbsp;&mdash;&copy;&hellip;&eacute; &chips; &AMP; &x;</rss>")
        assertEquals("<rss>&#160;&#8212;&#169;&#8230;&#233; &amp;chips; &amp;AMP; &amp;x;</rss>", result.xml)
        assertEquals(5, result.repairs.htmlEntities)
        assertEquals(3, result.repairs.bareAmpersands)
    }

    @Test
    fun `CDATA and comments are copied as written`() {
        val xml = "<rss><![CDATA[a & b &nbsp; <p>]]><!-- c & d --></rss>"
        assertEquals(xml, ready(xml).xml)
    }

    @Test
    fun `characters illegal in XML are removed everywhere, valid pairs are kept`() {
        val result = ready("<rss>a\u0001b\u000Bc${NONCHARACTER}d\uD800e\uDC00f🎙g<![CDATA[h\u0000i]]>\t\n\r</rss>")
        assertEquals("<rss>abcdef🎙g<![CDATA[hi]]>\t\n\r</rss>", result.xml)
        assertEquals(6, result.repairs.illegalCharacters)
    }

    @Test
    fun `references to illegal characters are dropped`() {
        val result = ready("<rss>a&#0;b&#x1;c&#xFFFE;d&#xD800;e&#x110000;f&#99999999999999999999;g&#9;h</rss>")
        assertEquals("<rss>abcdefg&#9;h</rss>", result.xml)
        assertEquals(6, result.repairs.illegalCharacters)
    }

    @Test
    fun `the XML declaration and a leading BOM character are removed`() {
        assertEquals("<rss/>", ready("$BOM<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss/>").xml)
    }

    @Test
    fun `a DOCTYPE that declares entities is refused`() {
        for (doctype in listOf(
            "<!DOCTYPE rss [<!ENTITY a \"b\">]>",
            "<!DOCTYPE rss [ <!ENTITY x SYSTEM \"file:///etc/passwd\"> ]>",
            "<!DOCTYPE rss [<!ENTITY % p SYSTEM \"http://evil.example.test/x.dtd\"> %p;]>",
            "<!DOCTYPE rss [%p;]>",
            "<!doctype rss [<!entity a \"b\">]>",
            "<!DOCTYPE rss PUBLIC \"x\" \"y\" [<!ATTLIST a b CDATA \"]\"><!ENTITY a \"b\">]>",
        )) {
            assertEquals(doctype, ParseProblem.EntityDeclaration, rejected("<?xml version=\"1.0\"?>$doctype<rss/>"))
        }
    }

    @Test
    fun `a DOCTYPE without entity declarations is removed`() {
        val result = ready(
            "<?xml version=\"1.0\"?>\n<!-- hi -->\n<!DOCTYPE rss PUBLIC \"-//X//DTD RSS 0.91//EN\" " +
                "\"http://dtd.example.test/rss-0.91.dtd\">\n<rss/>",
        )
        assertEquals("\n<!-- hi -->\n\n<rss/>", result.xml)
        assertTrue(result.repairs.doctypeRemoved)
    }

    @Test
    fun `entity or other markup declarations outside the DOCTYPE are refused`() {
        assertEquals(ParseProblem.EntityDeclaration, rejected("<rss><!ENTITY a \"b\"></rss>"))
        assertEquals(ParseProblem.EntityDeclaration, rejected("<rss></rss><!DOCTYPE rss [<!ENTITY a \"b\">]>"))
        assertTrue(rejected("<rss><!ELEMENT a ANY></rss>") is ParseProblem.Malformed)
        assertTrue(rejected("<rss><!DOCTYPE x></rss>") is ParseProblem.Malformed)
    }

    @Test
    fun `an unterminated DOCTYPE is malformed`() {
        assertTrue(rejected("<!DOCTYPE rss [<!ATTLIST a b CDATA \"x\">") is ParseProblem.Malformed)
    }

    @Test
    fun `web pages are recognised`() {
        for (page in listOf(
            "<!DOCTYPE html><html><body>x</body></html>",
            "<!doctype HTML>\n<p>x",
            "<html><head><title>404</title></head></html>",
            "  \n<!-- server --><HTML lang=\"en\">",
            "<head><meta charset=utf-8></head>",
            "<body>Error</body>",
            "<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"x\"><html/>",
        )) {
            assertEquals(page, ParseProblem.HtmlPage, rejected(page))
        }
    }

    @Test
    fun `feeds are not mistaken for web pages`() {
        ready("<rss><channel><description>&lt;html&gt;<![CDATA[<html>]]></description></channel></rss>")
        ready("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>html</title></feed>")
    }

    @Test
    fun `missing namespace declarations for well-known prefixes are added to the root`() {
        val result = ready("<rss version=\"2.0\"><channel><itunes:author>A</itunes:author><podcast:guid>g</podcast:guid></channel></rss>")
        assertEquals(listOf("itunes", "podcast"), result.repairs.namespacesAdded)
        assertTrue(result.xml, result.xml.startsWith("<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\""))
        assertTrue("xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"" in result.xml)
    }

    @Test
    fun `declared namespaces are left alone`() {
        val xml = "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><itunes:author>A</itunes:author></rss>"
        val result = ready(xml)
        assertEquals(xml, result.xml)
        assertEquals(emptyList<String>(), result.repairs.namespacesAdded)
    }

    @Test
    fun `tags with too many attributes are refused before the parser sees them`() {
        val attributes = (1..300).joinToString(" ") { "a$it=\"$it\"" }
        assertEquals(ParseProblem.TooManyAttributes(256), rejected("<rss><item $attributes/></rss>"))
        // Equals signs inside quoted values are not attributes.
        ready("<rss><enclosure url=\"https://x.example.com/?${(1..300).joinToString("&amp;") { "a=$it" }}\"/></rss>")
    }

    @Test
    fun `the byte limit is checked before decoding`() {
        val limits = XmlLimits(maxBytes = 10)
        val result = XmlPrepass.run("<rss>12345678</rss>".toByteArray(), null, limits, Ticker.SYSTEM)
        assertEquals(PrepassResult.Rejected(ParseProblem.TooLarge(10)), result)
    }

    @Test
    fun `the time limit applies to the pre-pass`() {
        val ticker = FakeTicker(step = 20.seconds.inWholeNanoseconds)
        val result = clean("<rss>" + "a & b ".repeat(200_000) + "</rss>", ticker = ticker)
        assertEquals(PrepassResult.Rejected(ParseProblem.TimeLimit), result)
    }

    @Test
    fun `empty input is malformed, not a crash`() {
        assertTrue(rejected("") is ParseProblem.Malformed)
        assertTrue(rejected("   ") is ParseProblem.Malformed)
    }

    @Test
    fun `nothing in the output is an unknown entity reference`() {
        val result = ready("<rss>&a;&b;&;&#;&#x;&#xZZ;&#12a;&" + "z".repeat(100) + ";</rss>")
        val refs = Regex("&([^;]*);").findAll(result.xml).map { it.groupValues[1] }.toList()
        assertFalse(result.xml, refs.any { it != "amp" && !it.startsWith("#") })
    }
}
