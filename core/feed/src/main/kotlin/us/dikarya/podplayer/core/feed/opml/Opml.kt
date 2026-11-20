/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.opml

import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.xmlpull.v1.XmlPullParserException
import us.dikarya.podplayer.core.feed.FeedParser
import us.dikarya.podplayer.core.feed.FieldLimits
import us.dikarya.podplayer.core.feed.ParseProblem
import us.dikarya.podplayer.core.feed.xml.Deadline
import us.dikarya.podplayer.core.feed.xml.PrepassResult
import us.dikarya.podplayer.core.feed.xml.Repairs
import us.dikarya.podplayer.core.feed.xml.SafeXmlReader
import us.dikarya.podplayer.core.feed.xml.Ticker
import us.dikarya.podplayer.core.feed.xml.XmlLimitException
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.feed.xml.XmlPrepass
import us.dikarya.podplayer.core.feed.xml.configureSafely
import us.dikarya.podplayer.core.feed.xml.isLegalChar
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.UrlProblem
import us.dikarya.podplayer.core.net.redact

/** One subscription in an OPML file. [feedUrl] may carry a private token: never log it unredacted. */
data class OpmlFeed(val feedUrl: String, val title: String? = null, val siteUrl: String? = null) {
    override fun toString() = "OpmlFeed(${redact(feedUrl)}, $title, ${siteUrl?.let(::redact)})"
}

sealed interface OpmlSkipReason {
    /** An outline with an empty `xmlUrl`. */
    data object MissingUrl : OpmlSkipReason

    data class InvalidUrl(val problem: UrlProblem) : OpmlSkipReason

    /** The same feed appeared earlier in the file. */
    data object Duplicate : OpmlSkipReason
}

/** An outline that wasn't imported, for the preview. [title] is the outline's own text, capped. */
data class OpmlSkip(val title: String?, val reason: OpmlSkipReason)

sealed interface OpmlParseResult {
    /** What the import preview shows (SPEC §4: import adds feeds with a preview and confirmation). */
    data class Success(
        val title: String?,
        val feeds: List<OpmlFeed>,
        val skipped: List<OpmlSkip>,
        val repairs: Repairs,
    ) : OpmlParseResult

    data class Failure(val problem: ParseProblem) : OpmlParseResult
}

/**
 * OPML import with the feed parser's safety rules (SPEC §4): the same pre-pass, limits (outlines count as
 * items) and URL policy. Nested category outlines are flattened in document order.
 */
class OpmlParser(
    private val parsers: XmlParserProvider,
    private val limits: XmlLimits = XmlLimits(),
    private val ticker: Ticker = Ticker.SYSTEM,
) {
    fun parse(body: ByteArray, contentType: String? = null): OpmlParseResult {
        val deadline = Deadline(ticker, limits.maxDuration)
        val prepared = when (val result = XmlPrepass.run(body, contentType, limits, deadline)) {
            is PrepassResult.Ready -> result
            is PrepassResult.Rejected -> return OpmlParseResult.Failure(result.problem)
        }
        val parser = parsers.newParser()
        parser.configureSafely()
        return try {
            parser.setInput(StringReader(prepared.xml))
            val reader = SafeXmlReader(parser, limits, deadline)
            val root = reader.moveToRoot()
            if (!root.equals("opml", ignoreCase = true)) {
                return OpmlParseResult.Failure(ParseProblem.NotAFeed(FeedParser.safeName(root)))
            }
            val result = Reader(reader).read(prepared.repairs)
            // The reader checks the clock only every so many events; the budget must hold to the end.
            deadline.check()
            result
        } catch (e: XmlLimitException) {
            OpmlParseResult.Failure(e.problem)
        } catch (e: XmlPullParserException) {
            OpmlParseResult.Failure(FeedParser.malformed(parser, e.lineNumber, e.columnNumber))
        } catch (_: IOException) {
            OpmlParseResult.Failure(FeedParser.malformed(parser, -1, -1))
        } catch (_: RuntimeException) {
            OpmlParseResult.Failure(FeedParser.malformed(parser, -1, -1))
        }
    }

    private class Reader(private val reader: SafeXmlReader) {
        private val feeds = mutableListOf<OpmlFeed>()
        private val skipped = mutableListOf<OpmlSkip>()
        private val seen = HashSet<String>()
        private var title: String? = null

        fun read(repairs: Repairs): OpmlParseResult.Success {
            reader.forEachChild {
                when (reader.parser.name.lowercase()) {
                    "head" -> head()
                    "body" -> outlines()
                    else -> reader.skip()
                }
            }
            return OpmlParseResult.Success(title, feeds.toList(), skipped.toList(), repairs)
        }

        private fun head() {
            reader.forEachChild {
                if (reader.parser.name.equals("title", ignoreCase = true) && title == null) {
                    title = collapse(reader.readText(FieldLimits.TITLE * 4))
                } else {
                    reader.skip()
                }
            }
        }

        /** The outlines inside the current element, depth-first. The reader's depth limit bounds recursion. */
        private fun outlines() {
            reader.forEachChild {
                if (reader.parser.name.equals("outline", ignoreCase = true)) {
                    reader.countItem()
                    outline()
                    outlines()
                } else {
                    reader.skip()
                }
            }
        }

        private fun outline() {
            val rawUrl = reader.attribute("xmlUrl") ?: return
            val name = collapse(reader.attribute("text")) ?: collapse(reader.attribute("title"))
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                skipped += OpmlSkip(name, OpmlSkipReason.MissingUrl)
                return
            }
            when (val check = UrlPolicy.checkFeedUrl(trimmed)) {
                is UrlCheck.Rejected -> skipped += OpmlSkip(name, OpmlSkipReason.InvalidUrl(check.problem))
                is UrlCheck.Allowed -> {
                    if (!seen.add(check.url.toString())) {
                        skipped += OpmlSkip(name, OpmlSkipReason.Duplicate)
                    } else {
                        val site = reader.attribute("htmlUrl")?.trim()?.takeIf { UrlPolicy.checkFeedUrl(it) is UrlCheck.Allowed }
                        feeds += OpmlFeed(trimmed, name, site)
                    }
                }
            }
        }

        private fun collapse(text: String?): String? =
            text?.trim()?.replace(whitespace, " ")?.take(FieldLimits.TITLE)?.ifEmpty { null }
    }

    private companion object {
        val whitespace = Regex("\\s+")
    }
}

/** OPML 2.0 export of all subscriptions (SPEC §4). Only written when the owner asks for it. */
object OpmlWriter {
    const val DEFAULT_TITLE = "Podplayer subscriptions"

    private val rfc822 = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).withZone(ZoneOffset.UTC)

    fun write(feeds: List<OpmlFeed>, title: String = DEFAULT_TITLE, createdAt: Instant? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head>\n")
        append("    <title>").append(escape(title)).append("</title>\n")
        if (createdAt != null) append("    <dateCreated>").append(rfc822.format(createdAt)).append("</dateCreated>\n")
        append("  </head>\n  <body>\n")
        for (feed in feeds) {
            val text = escape(feed.title.orEmpty())
            append("    <outline type=\"rss\" text=\"").append(text).append('"')
            if (feed.title != null) append(" title=\"").append(text).append('"')
            append(" xmlUrl=\"").append(escape(feed.feedUrl)).append('"')
            if (feed.siteUrl != null) append(" htmlUrl=\"").append(escape(feed.siteUrl)).append('"')
            append("/>\n")
        }
        append("  </body>\n</opml>\n")
    }

    /** Escapes for both text and attributes, and drops what XML can't carry (and line breaks in attributes). */
    private fun escape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    append(c).append(value[i + 1])
                    i++
                }
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\n' || c == '\r' || c == '\t' -> append(' ')
                isLegalChar(c) -> append(c)
            }
            i++
        }
    }
}
