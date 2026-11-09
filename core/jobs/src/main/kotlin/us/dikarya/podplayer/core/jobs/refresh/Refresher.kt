/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.refresh

import android.util.Xml
import us.dikarya.podplayer.core.feed.FeedParser
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider

/** What to refresh. */
sealed interface RefreshRequest {
    /** Every subscription, including feeds backing off: the owner asked. */
    data object All : RefreshRequest

    /** The periodic refresh: every feed except those backing off after errors (SPEC §8). */
    data object Due : RefreshRequest

    /** One feed, now, backoff or not: the owner asked. */
    data class Feed(val feedId: Long) : RefreshRequest
}

data class RefreshSummary(
    /** Feeds fetched (or refused before fetching). */
    val attempted: Int,
    val updated: Int,
    val notModified: Int,
    val failed: Int,
    val newEpisodes: Int,
) {
    companion object {
        val Empty = RefreshSummary(0, 0, 0, 0, 0)
    }
}

/** Refreshes feeds; [FeedRefresher] in the app, a fake in worker tests. */
fun interface Refresher {
    suspend fun refresh(request: RefreshRequest): RefreshSummary
}

/**
 * Per-feed exponential backoff (SPEC §8, LESSONS "Refresh"): after the n-th failure in a row the feed waits
 * [baseMs] × 2^(n-1), at most [maxMs], before the periodic refresh tries it again. Success resets it.
 */
class RefreshBackoff(private val baseMs: Long = 30 * 60_000L, private val maxMs: Long = 24 * 3_600_000L) {
    fun delayMs(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 30)
        return (baseMs shl doublings).coerceAtMost(maxMs)
    }
}

/** The feed parser over Android's own `XmlPullParser` (SPEC §4). */
fun androidFeedParser(): FeedParser = FeedParser(XmlParserProvider { Xml.newPullParser() })
