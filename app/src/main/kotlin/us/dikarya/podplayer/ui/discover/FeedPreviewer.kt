/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.util.Xml
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.dikarya.podplayer.core.feed.FeedParseResult
import us.dikarya.podplayer.core.feed.FeedParser
import us.dikarya.podplayer.core.feed.ParsedFeed
import us.dikarya.podplayer.core.feed.opml.OpmlFeed
import us.dikarya.podplayer.core.feed.opml.OpmlParseResult
import us.dikarya.podplayer.core.feed.opml.OpmlParser
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.redact

/** What the owner sees before subscribing to a typed feed URL. [url] is the policy's canonical form. */
class FeedPreview(val url: String, val feed: ParsedFeed) {
    val title: String? get() = AppleSearch.clean(feed.title)
    val author: String? get() = AppleSearch.clean(feed.author)
    val episodeCount: Int get() = feed.episodes.count { it.enclosure != null }
    val latestTitles: List<String> get() = feed.episodes.asSequence().filter { it.enclosure != null }
        .mapNotNull { AppleSearch.clean(it.title) }.take(LATEST).toList()
    val imageUrl: String? get() = feed.imageUrl
    val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)

    /** The host the owner typed, approved for this feed's requests (SPEC §3). */
    val approvedHost: String? get() = url.toHttpUrlOrNull()?.host

    override fun toString() = "FeedPreview(${redact(url)}, $title)"

    private companion object {
        const val LATEST = 3
    }
}

sealed interface PreviewOutcome {
    class Ready(val preview: FeedPreview) : PreviewOutcome {
        override fun toString() = "Ready($preview)"
    }

    data class Failed(val problem: DiscoverProblem) : PreviewOutcome
}

/**
 * Fetches and parses a feed URL the owner typed or pasted, without subscribing. The owner typed it, so its host
 * is approved for this request even on a private network (SPEC §3); the same gate, limits and parser as a refresh.
 */
class FeedPreviewer(
    private val gate: GateFetch,
    private val parser: FeedParser,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun preview(raw: String): PreviewOutcome {
        val url = when (val check = UrlPolicy.checkFeedUrl(raw)) {
            is UrlCheck.Allowed -> check.url
            is UrlCheck.Rejected -> return PreviewOutcome.Failed(DiscoverProblem.BadUrl(check.problem))
        }
        return when (val result = gate.fetch(url, BodyLimit.FEED, AddressPolicy.ownerTyped(url))) {
            is FetchResult.Failure -> PreviewOutcome.Failed(DiscoverProblem.Net(result.error))
            is FetchResult.NotModified -> PreviewOutcome.Failed(DiscoverProblem.Net(NetError.HttpStatus(304)))
            is FetchResult.Success -> when (val parsed = withContext(parseDispatcher) { parser.parse(result.body, result.contentType) }) {
                is FeedParseResult.Failure -> PreviewOutcome.Failed(DiscoverProblem.NotAFeed(parsed.problem))
                is FeedParseResult.Success -> PreviewOutcome.Ready(FeedPreview(url.toString(), parsed.feed))
            }
        }
    }
}

sealed interface OpmlOutcome {
    /** The import preview (SPEC §4): what would be added, and how many outlines were left out. */
    data class Ready(val title: String?, val feeds: List<OpmlFeed>, val skipped: Int) : OpmlOutcome

    data class Failed(val problem: DiscoverProblem) : OpmlOutcome
}

/** An OPML file the owner picked (Storage Access Framework): hostile input, read under the OPML size limit. */
object OpmlFile {
    suspend fun read(
        parser: OpmlParser = OpmlParser(XmlParserProvider { Xml.newPullParser() }),
        open: () -> InputStream?,
    ): OpmlOutcome = withContext(Dispatchers.IO) {
        val unreadable = OpmlOutcome.Failed(DiscoverProblem.FileUnreadable)
        val bytes = try {
            val stream = open() ?: return@withContext unreadable
            stream.use { readLimited(it, BodyLimit.OPML.maxBytes) } ?: return@withContext OpmlOutcome.Failed(DiscoverProblem.FileTooLarge)
        } catch (_: IOException) {
            return@withContext unreadable
        } catch (_: SecurityException) {
            return@withContext unreadable
        } catch (_: IllegalArgumentException) {
            // A content URI the provider doesn't understand.
            return@withContext unreadable
        }
        when (val result = withContext(Dispatchers.Default) { parser.parse(bytes) }) {
            is OpmlParseResult.Failure -> OpmlOutcome.Failed(DiscoverProblem.NotOpml(result.problem))
            is OpmlParseResult.Success -> OpmlOutcome.Ready(AppleSearch.clean(result.title), result.feeds, result.skipped.size)
        }
    }

    /** The whole stream, or null when it holds more than [maxBytes]. Never reads much past the limit. */
    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
    }

    private const val BUFFER = 64 * 1024
}
