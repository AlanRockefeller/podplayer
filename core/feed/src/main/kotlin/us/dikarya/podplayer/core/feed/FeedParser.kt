/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import java.io.IOException
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import us.dikarya.podplayer.core.feed.xml.Deadline
import us.dikarya.podplayer.core.feed.xml.Namespaces
import us.dikarya.podplayer.core.feed.xml.PrepassResult
import us.dikarya.podplayer.core.feed.xml.SafeXmlReader
import us.dikarya.podplayer.core.feed.xml.Ticker
import us.dikarya.podplayer.core.feed.xml.XmlLimitException
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.feed.xml.XmlPrepass
import us.dikarya.podplayer.core.feed.xml.configureSafely

/**
 * RSS 2.0 (with iTunes and Podcasting 2.0 tags) and Atom (SPEC §4). Never throws for bad input: every
 * problem is a [FeedParseResult.Failure] with a typed [ParseProblem], and the caller keeps its last good
 * copy of the feed.
 */
class FeedParser(
    private val parsers: XmlParserProvider,
    private val limits: XmlLimits = XmlLimits(),
    private val ticker: Ticker = Ticker.SYSTEM,
) {
    /** [body] is the response body as received; [contentType] is the HTTP header, used for its charset. */
    fun parse(body: ByteArray, contentType: String? = null): FeedParseResult {
        val deadline = Deadline(ticker, limits.maxDuration)
        val prepared = when (val result = XmlPrepass.run(body, contentType, limits, deadline)) {
            is PrepassResult.Ready -> result
            is PrepassResult.Rejected -> return FeedParseResult.Failure(result.problem)
        }
        val parser = parsers.newParser()
        parser.configureSafely()
        return try {
            parser.setInput(StringReader(prepared.xml))
            val reader = SafeXmlReader(parser, limits, deadline)
            val root = reader.moveToRoot()
            val namespace = parser.namespace.orEmpty()
            val context = FeedContext(reader)
            val feed = when {
                root == "rss" -> RssReader(context).read()
                root == "feed" && AtomReader.isAtomNamespace(namespace) -> AtomReader(context, namespace).read()
                else -> return FeedParseResult.Failure(ParseProblem.NotAFeed(safeName(root)))
            }
            val warnings = warnings(prepared, context, feed)
            // The reader checks the clock only every so many events; the budget must hold to the end.
            deadline.check()
            FeedParseResult.Success(feed, warnings)
        } catch (e: XmlLimitException) {
            FeedParseResult.Failure(e.problem)
        } catch (e: XmlPullParserException) {
            FeedParseResult.Failure(malformed(parser, e.lineNumber, e.columnNumber))
        } catch (_: IOException) {
            FeedParseResult.Failure(malformed(parser, -1, -1))
        } catch (_: RuntimeException) {
            // Pull parsers throw assorted runtime exceptions on broken input; none of them is our bug.
            FeedParseResult.Failure(malformed(parser, -1, -1))
        }
    }

    private fun warnings(prepared: PrepassResult.Ready, context: FeedContext, feed: ParsedFeed): List<FeedWarning> {
        val warnings = mutableListOf<FeedWarning>()
        if (prepared.repairs.any) warnings += FeedWarning.Repaired(prepared.repairs)
        val duplicateGuids = feed.episodes.mapNotNull { it.guid }.groupingBy { it }.eachCount().count { it.value > 1 }
        if (duplicateGuids > 0) warnings += FeedWarning.DuplicateGuids(duplicateGuids)
        if (context.rejectedUrls > 0) warnings += FeedWarning.UrlsRejected(context.rejectedUrls)
        val truncated = context.truncated
        if (truncated > 0) warnings += FeedWarning.FieldsTruncated(truncated)
        return warnings
    }

    internal companion object {
        fun malformed(parser: XmlPullParser, line: Int, column: Int): ParseProblem.Malformed =
            ParseProblem.Malformed(
                if (line > 0) line else parser.lineNumber.coerceAtLeast(0),
                if (column > 0) column else parser.columnNumber.coerceAtLeast(0),
            )

        /** A root element name fit for a log line: name characters only, short. */
        fun safeName(name: String): String =
            name.filter { it.isLetterOrDigit() || it in "_.:-" }.take(40).ifEmpty { "?" }

        fun namespaceKind(namespace: String): NamespaceKind = when {
            namespace.isEmpty() -> NamespaceKind.None
            Namespaces.isItunes(namespace) -> NamespaceKind.Itunes
            Namespaces.isPodcast(namespace) -> NamespaceKind.Podcast
            Namespaces.isContent(namespace) -> NamespaceKind.Content
            Namespaces.isAtom(namespace) -> NamespaceKind.Atom
            Namespaces.isDublinCore(namespace) -> NamespaceKind.DublinCore
            else -> NamespaceKind.Other
        }
    }
}

internal enum class NamespaceKind { None, Itunes, Podcast, Content, Atom, DublinCore, Other }
