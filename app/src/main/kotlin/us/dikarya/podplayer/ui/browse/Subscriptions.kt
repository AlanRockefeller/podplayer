/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.feed.ParsedFeed
import us.dikarya.podplayer.core.feed.opml.OpmlFeed
import us.dikarya.podplayer.core.net.UrlProblem

sealed interface SubscribeOutcome {
    data class Subscribed(val feedId: Long) : SubscribeOutcome

    data class AlreadySubscribed(val feedId: Long) : SubscribeOutcome

    data class Refused(val problem: UrlProblem) : SubscribeOutcome

    data object LibraryClosed : SubscribeOutcome
}

data class ImportSummary(val added: Int, val alreadySubscribed: Int, val refused: Int)

/**
 * Subscribing from Discover. [refresh] fetches a feed (or all, for null) when a network is there; it is the
 * jobs layer's refresh, so the fetch goes through the gate with the feed's own policy.
 */
class Subscriptions(
    private val repositories: suspend () -> Repositories?,
    private val refresh: (feedId: Long?) -> Unit,
) {
    /**
     * [ownerTyped] is true only for an address the owner typed or pasted (its host is approved, SPEC §3); false for
     * search results and OPML imports. A [preview] already fetched is applied at once, so the show page has its
     * episodes without waiting for the refresh.
     */
    suspend fun subscribe(url: String, ownerTyped: Boolean, preview: ParsedFeed? = null): SubscribeOutcome {
        val repos = repositories() ?: return SubscribeOutcome.LibraryClosed
        return when (val result = repos.feeds.subscribe(url, ownerTyped)) {
            is SubscribeResult.Refused -> SubscribeOutcome.Refused(result.problem)
            is SubscribeResult.AlreadySubscribed -> SubscribeOutcome.AlreadySubscribed(result.feedId)
            is SubscribeResult.Subscribed -> {
                if (preview != null) {
                    // Same parser and limits as a refresh; the refresh below records validators and redirects.
                    repos.feeds.applyRefresh(result.feedId, preview)
                }
                refresh(result.feedId)
                SubscribeOutcome.Subscribed(result.feedId)
            }
        }
    }

    /** OPML import (SPEC §4): every feed with [ownerTyped] false, then one refresh of everything. */
    suspend fun importAll(feeds: List<OpmlFeed>): ImportSummary? {
        val repos = repositories() ?: return null
        var added = 0
        var already = 0
        var refused = 0
        for (feed in feeds) {
            when (repos.feeds.subscribe(feed.feedUrl, ownerTyped = false)) {
                is SubscribeResult.Subscribed -> added++
                is SubscribeResult.AlreadySubscribed -> already++
                is SubscribeResult.Refused -> refused++
            }
        }
        if (added > 0) refresh(null)
        return ImportSummary(added, already, refused)
    }
}
