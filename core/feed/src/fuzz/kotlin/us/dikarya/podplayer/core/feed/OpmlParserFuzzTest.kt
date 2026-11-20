/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import com.code_intelligence.jazzer.junit.FuzzTest
import us.dikarya.podplayer.core.feed.opml.OpmlParseResult
import us.dikarya.podplayer.core.feed.opml.OpmlParser
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.testing.Fixtures

/**
 * OPML files are hostile input too. Whatever the bytes, the parser returns a typed result: it never throws, and
 * an import offers only http(s) feeds, within the item limit. Seeds: the OPML corpus and samples.
 *
 * `./gradlew :core:feed:fuzzOpml` fuzzes it; `check` replays the seeds and saved findings.
 */
class OpmlParserFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun opml(body: ByteArray) {
        val result = parser.parse(body, null) as? OpmlParseResult.Success ?: return
        check(result.feeds.size + result.skipped.size <= LIMITS.maxItems) { "${result.feeds.size} feeds, over the item limit" }
        for (feed in result.feeds) {
            check(isHttp(feed.feedUrl)) { "a feed URL that isn't http(s): ${feed.feedUrl}" }
        }
    }

    private companion object {
        /** Schemes are case-insensitive: `Https://` is https (a CI fuzz run's first false alarm). */
        fun isHttp(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        val LIMITS = XmlLimits(maxBytes = 1024 * 1024, maxItems = 200)
        val parser = OpmlParser(XmlParserProvider { Fixtures.xmlPullParser() }, LIMITS)
    }
}
