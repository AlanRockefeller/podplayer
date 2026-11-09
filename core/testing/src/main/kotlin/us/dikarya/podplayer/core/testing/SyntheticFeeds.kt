/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.testing

import java.nio.charset.Charset

/**
 * Generated fixtures: the ones that are too big to commit or need exact bytes.
 * Everything is invented; hosts are example.com and the reserved .test domain.
 */
object SyntheticFeeds {
    const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    /** One `<item>`, numbered [n]. */
    fun item(n: Int, guid: String? = "guid-$n", extra: String = ""): String = buildString {
        append("<item><title>Episode ").append(n).append("</title>")
        if (guid != null) append("<guid isPermaLink=\"false\">").append(guid).append("</guid>")
        append("<pubDate>").append(rfc822Day(n)).append("</pubDate>")
        append("<enclosure url=\"https://media.example.com/ep").append(n)
            .append(".mp3\" type=\"audio/mpeg\" length=\"").append(1000 + n).append("\"/>")
        append("<itunes:duration>").append(1800 + n % 600).append("</itunes:duration>")
        append("<description><![CDATA[<p>Notes for episode ").append(n).append(" at 12:34.</p>]]></description>")
        append(extra)
        append("</item>\n")
    }

    /** An RSS feed with [items] items. */
    fun rss(items: Int, title: String = "Synthetic Show"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<rss version=\"2.0\" xmlns:itunes=\"").append(ITUNES_NS).append("\"><channel>")
        append("<title>").append(title).append("</title>\n")
        for (n in items downTo 1) append(item(n))
        append("</channel></rss>\n")
    }

    /** A feed of at least [bytes] bytes: one item with a huge description. */
    fun oversized(bytes: Int): ByteArray {
        val head = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Big</title><item><title>x</title>" +
            "<enclosure url=\"https://media.example.com/big.mp3\" type=\"audio/mpeg\"/><description>"
        val tail = "</description></item></channel></rss>"
        val filler = bytes - head.length - tail.length
        return (head + "a".repeat(maxOf(filler, 0)) + tail).toByteArray(Charsets.UTF_8)
    }

    /**
     * A feed whose deepest element sits at [depth] (the `rss` root is depth 1, as XmlPullParser counts).
     * The nesting is inside an item, where real feeds put unknown extension elements.
     */
    fun nested(depth: Int): String {
        // rss(1) > channel(2) > item(3) > x(4) ... x(depth)
        val wrappers = maxOf(depth - 3, 0)
        return buildString {
            append("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Deep</title><item><title>Deep item</title>")
            append("<enclosure url=\"https://media.example.com/deep.mp3\" type=\"audio/mpeg\"/>")
            repeat(wrappers) { append("<x>") }
            append("deep")
            repeat(wrappers) { append("</x>") }
            append("</item></channel></rss>")
        }
    }

    /** [xml] encoded in [charset], with the matching byte order mark in front when [bom] is set. */
    fun encoded(xml: String, charset: Charset, bom: Boolean): ByteArray {
        val body = xml.toByteArray(charset)
        if (!bom) return body
        val mark = when (charset.name().uppercase()) {
            "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> throw IllegalArgumentException("No BOM for $charset")
        }
        return mark + body
    }

    /** A small feed with non-ASCII text, for the encoding tests. */
    fun accented(encodingDeclaration: String?): String = buildString {
        append("<?xml version=\"1.0\"")
        if (encodingDeclaration != null) append(" encoding=\"").append(encodingDeclaration).append('"')
        append("?>\n<rss version=\"2.0\"><channel><title>Café crème über</title>")
        append("<item><title>Naïve résumé</title><guid>acc-1</guid>")
        append("<enclosure url=\"https://media.example.com/acc.mp3\" type=\"audio/mpeg\"/></item>")
        append("</channel></rss>")
    }

    private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** A plausible RFC 822 date, one day apart per item (the day name may be wrong, as in real feeds). */
    private fun rfc822Day(n: Int): String {
        val day = 1 + n % 28
        val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[n / 28 % 12]
        val year = 2000 + n / (28 * 12)
        return "${days[n % 7]}, ${day.toString().padStart(2, '0')} $month $year 10:00:00 GMT"
    }
}
