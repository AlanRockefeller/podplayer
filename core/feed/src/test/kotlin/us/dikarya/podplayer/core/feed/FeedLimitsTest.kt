/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.feed.xml.XmlLimits
import us.dikarya.podplayer.core.testing.SyntheticFeeds

/** SPEC §4 limits: 20 MB, 10,000 items, 10 s parse time, element depth 64. M1-S3 "Done when". */
class FeedLimitsTest {
    @Test
    fun `the defaults are the spec's`() {
        val limits = XmlLimits()
        assertEquals(20 * 1024 * 1024, limits.maxBytes)
        assertEquals(10_000, limits.maxItems)
        assertEquals(10.seconds, limits.maxDuration)
        assertEquals(64, limits.maxDepth)
    }

    @Test
    fun `a 10,000-item feed parses well inside the time limit`() {
        val bytes = SyntheticFeeds.rss(10_000).toByteArray()
        parseFeed(bytes) // warm up the JIT, as a long-running app would be
        lateinit var result: FeedParseResult
        val took = measureTime { result = parseFeed(bytes) }
        assertEquals(10_000, result.feed().episodes.size)
        // The parser enforces 10 s itself; a quarter of that leaves room for slow phones.
        assertTrue("took $took", took < 2.5.seconds)
    }

    @Test
    fun `10,001 items are refused`() {
        assertEquals(ParseProblem.TooManyItems(10_000), parseFeed(SyntheticFeeds.rss(10_001).toByteArray()).problem())
    }

    @Test
    fun `the time limit stops the parse`() {
        val ticker = FakeTicker(step = 1.seconds.inWholeNanoseconds)
        val result = parseFeed(SyntheticFeeds.rss(2_000).toByteArray(), ticker = ticker)
        assertEquals(ParseProblem.TimeLimit, result.problem())
    }

    @Test
    fun `a slow clock that stays under the limit still parses`() {
        val ticker = FakeTicker(step = 1)
        assertEquals(500, parseFeed(SyntheticFeeds.rss(500).toByteArray(), ticker = ticker).feed().episodes.size)
    }

    @Test
    fun `limits can be tightened`() {
        val limits = XmlLimits(maxDepth = 5)
        assertEquals(ParseProblem.TooDeep(5), parseFeed(SyntheticFeeds.nested(6).toByteArray(), limits = limits).problem())
    }
}
