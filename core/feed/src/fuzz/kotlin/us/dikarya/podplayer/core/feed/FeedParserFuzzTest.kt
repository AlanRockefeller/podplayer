/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import com.code_intelligence.jazzer.junit.FuzzTest
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.testing.Fixtures

/**
 * CLAUDE.md: every feed is hostile, and bad input never crashes the app. Whatever the bytes, the feed parser
 * (RSS, Atom, Podcasting 2.0) returns a typed result: it never throws, and a parsed feed stays inside its
 * limits and the URL policy. Seeds: the hostile corpus and the samples (see `build.gradle.kts`).
 *
 * `./gradlew :core:feed:fuzzFeed` fuzzes it; `check` replays the seeds and saved findings.
 */
class FeedParserFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun feed(body: ByteArray) {
        for (contentType in CONTENT_TYPES) checkFeed(parser.parse(body, contentType))
    }

    private fun checkFeed(result: FeedParseResult) {
        val feed = (result as? FeedParseResult.Success)?.feed ?: return
        check(feed.episodes.size <= LIMITS.maxItems) { "${feed.episodes.size} episodes, over the item limit" }
        for (episode in feed.episodes) {
            val url = episode.enclosure?.url ?: continue
            // Schemes are case-insensitive: `Https://` is https.
            check(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) { "an enclosure URL that isn't http(s): $url" }
        }
    }

    private companion object {
        /** Small limits, so the fuzzer reaches the limit paths too. */
        val LIMITS = XmlLimits(maxBytes = 1024 * 1024, maxItems = 200)
        val parser = FeedParser(XmlParserProvider { Fixtures.xmlPullParser() }, LIMITS)

        /** No header, and one that disagrees with most documents' own encoding. */
        val CONTENT_TYPES = listOf(null, "application/rss+xml; charset=ISO-8859-1")
    }
}
