/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.xml

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.xmlpull.v1.XmlPullParser
import us.dikarya.podplayer.core.feed.ParseProblem

/**
 * Makes a new [XmlPullParser]. SPEC §4: the parsers are written against the interface; Android passes the
 * platform parser (`Xml.newPullParser()`), JVM tests pass kxml2.
 */
fun interface XmlParserProvider {
    fun newParser(): XmlPullParser
}

/** A monotonic clock in nanoseconds, so tests can make time pass. */
fun interface Ticker {
    fun nanos(): Long

    companion object {
        val SYSTEM: Ticker = Ticker { System.nanoTime() }
    }
}

/** SPEC §4 limits. The same ones apply to OPML. */
class XmlLimits(
    val maxBytes: Int = 20 * MIB,
    /** Feed items or entries; OPML outlines. */
    val maxItems: Int = 10_000,
    /** Pre-pass plus parse. */
    val maxDuration: Duration = 10.seconds,
    /** Element depth as XmlPullParser counts it: the root element is 1. */
    val maxDepth: Int = 64,
    /** Pull parsers check attribute names for duplicates in quadratic time; this keeps that bounded. */
    val maxAttributesPerTag: Int = 256,
) {
    override fun toString() = "XmlLimits($maxBytes bytes, $maxItems items, $maxDuration, depth $maxDepth)"

    private companion object {
        const val MIB = 1024 * 1024
    }
}

/** Thrown inside the parsers when a limit is hit; turned into a typed result at the top. */
internal class XmlLimitException(val problem: ParseProblem) : RuntimeException(problem.logReason, null, false, false)

/** Tracks the time budget shared by the pre-pass and the parse. */
internal class Deadline(private val ticker: Ticker, limit: Duration) {
    private val start = ticker.nanos()
    private val budget = limit.inWholeNanoseconds

    fun check() {
        if (ticker.nanos() - start > budget) throw XmlLimitException(ParseProblem.TimeLimit)
    }
}

/** Namespace URIs the readers recognise. Publishers get the case and the scheme wrong, so matching is loose. */
internal object Namespaces {
    const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    const val PODCAST = "https://podcastindex.org/namespace/1.0"
    const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    const val ATOM = "http://www.w3.org/2005/Atom"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val MEDIA = "http://search.yahoo.com/mrss/"
    const val GOOGLEPLAY = "http://www.google.com/schemas/play-podcasts/1.0"

    /** Prefixes the pre-pass declares when a feed uses them without declaring them. */
    val wellKnownPrefixes: List<Pair<String, String>> = listOf(
        "itunes" to ITUNES,
        "podcast" to PODCAST,
        "content" to CONTENT,
        "atom" to ATOM,
        "media" to MEDIA,
        "googleplay" to GOOGLEPLAY,
        "dc" to DC,
    )

    fun isItunes(uri: String) = uri.equals(ITUNES, ignoreCase = true) ||
        uri.equals("https://www.itunes.com/dtds/podcast-1.0.dtd", ignoreCase = true)

    fun isPodcast(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.startsWith("https://podcastindex.org/namespace/1.0") ||
            lower.startsWith("http://podcastindex.org/namespace/1.0") ||
            lower.startsWith("https://github.com/podcastindex-org/podcast-namespace/blob/main/docs/1.0.md")
    }

    fun isContent(uri: String) = uri.equals(CONTENT, ignoreCase = true)

    fun isAtom(uri: String) = uri.equals(ATOM, ignoreCase = true)

    fun isDublinCore(uri: String) = uri.equals(DC, ignoreCase = true)
}

/**
 * The parser setup from SPEC §4: namespaces on, DOCTYPE processing off. The pre-pass has already removed
 * every DOCTYPE, so this is the second line of defence. kxml2 refuses to set the DOCTYPE feature at all
 * (it never processes DOCTYPEs), so a refusal there is fine as long as the feature reads back as off.
 */
internal fun XmlPullParser.configureSafely() {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    try {
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    } catch (_: Exception) {
        // Unsupported feature: the parser never processes DOCTYPEs.
    }
    check(!getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "DOCTYPE processing is on" }
    check(!getFeature(XmlPullParser.FEATURE_VALIDATION)) { "validation is on" }
}

/**
 * Walks a pull parser under the limits: every event checks the depth, and every [TIME_CHECK_EVERY]
 * events check the time. Helpers read text, markup and attributes with length caps.
 */
internal class SafeXmlReader(
    val parser: XmlPullParser,
    private val limits: XmlLimits,
    private val deadline: Deadline,
) {
    private var events = 0
    private var items = 0

    /** How many values were cut to their field limit. */
    var truncated = 0
        private set

    fun next(): Int {
        val event = parser.next()
        if (event == XmlPullParser.START_TAG && parser.depth > limits.maxDepth) {
            throw XmlLimitException(ParseProblem.TooDeep(limits.maxDepth))
        }
        if (++events % TIME_CHECK_EVERY == 0) deadline.check()
        return event
    }

    /** Counts one item (feed item, Atom entry, OPML outline) against the limit. */
    fun countItem() {
        if (++items > limits.maxItems) throw XmlLimitException(ParseProblem.TooManyItems(limits.maxItems))
    }

    /** Moves to the root element and returns its local name. */
    fun moveToRoot(): String {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG) {
            if (event == XmlPullParser.END_DOCUMENT) throw XmlLimitException(ParseProblem.Malformed(parser.lineNumber, parser.columnNumber))
            event = next()
        }
        return parser.name
    }

    /**
     * The document ended inside an element. Pull parsers report this as a plain end of document, but it means
     * the body was cut off (a server closing the connection early): what arrived is not the whole feed.
     */
    fun endedEarly(): Nothing = throw XmlLimitException(ParseProblem.Malformed(parser.lineNumber, parser.columnNumber))

    /** Calls [onChild] for each child element of the current element; [onChild] must consume it. */
    inline fun forEachChild(onChild: () -> Unit) {
        val depth = parser.depth
        while (true) {
            val event = next()
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
            if (event == XmlPullParser.END_DOCUMENT) endedEarly()
            if (event == XmlPullParser.START_TAG) onChild()
        }
    }

    /** Skips the current element and everything in it. */
    fun skip() {
        val depth = parser.depth
        while (true) {
            val event = next()
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
            if (event == XmlPullParser.END_DOCUMENT) endedEarly()
        }
    }

    /** The text inside the current element, ignoring any child tags, capped at [maxChars]. */
    fun readText(maxChars: Int): String {
        val out = CappedBuilder(maxChars)
        val depth = parser.depth
        while (true) {
            when (next()) {
                XmlPullParser.TEXT -> out.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.depth == depth) break
                XmlPullParser.END_DOCUMENT -> endedEarly()
            }
        }
        if (out.truncated) truncated++
        return out.toString()
    }

    /**
     * The content of the current element as markup: text as it was (entities already decoded, which is how
     * CDATA-wrapped HTML arrives), plus any child elements written back as HTML tags. Feeds sometimes put
     * unescaped XHTML in descriptions, and Atom has `type="xhtml"`. The result goes to the show-notes
     * sanitizer, never anywhere else.
     */
    fun readMarkup(maxChars: Int): String {
        // Text alone is already HTML (CDATA or escaped HTML) and is kept as is. Once there are child tags,
        // the text between them is element text, so it is escaped again when written back.
        val raw = CappedBuilder(maxChars)
        val out = CappedBuilder(maxChars)
        var hasChildren = false
        val depth = parser.depth
        while (true) {
            when (next()) {
                XmlPullParser.TEXT -> {
                    raw.append(parser.text)
                    out.append(escapeText(parser.text))
                }
                XmlPullParser.START_TAG -> {
                    hasChildren = true
                    out.append("<").append(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        out.append(" ").append(parser.getAttributeName(i)).append("=\"")
                            .append(escapeAttribute(parser.getAttributeValue(i))).append("\"")
                    }
                    out.append(">")
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == depth) break
                    out.append("</").append(parser.name).append(">")
                }
                XmlPullParser.END_DOCUMENT -> endedEarly()
            }
        }
        val result = if (hasChildren) out else raw
        if (result.truncated) truncated++
        return result.toString()
    }

    /** An attribute of the current element by local name, ignoring its namespace and case. */
    fun attribute(name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) return parser.getAttributeValue(i)
        }
        return null
    }

    /** Counts a value that had to be shortened, for the warning. */
    fun noteTruncated() {
        truncated++
    }

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun escapeText(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val TIME_CHECK_EVERY = 256
    }
}

/** A StringBuilder that stops growing at [max] characters and remembers that it did. */
internal class CappedBuilder(private val max: Int) {
    private val sb = StringBuilder()
    var truncated = false
        private set

    fun append(text: String): CappedBuilder {
        if (truncated) return this
        val room = max - sb.length
        if (text.length <= room) {
            sb.append(text)
        } else {
            sb.append(text, 0, room)
            truncated = true
        }
        return this
    }

    override fun toString(): String = sb.toString()
}
