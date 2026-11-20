/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.refresh

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.MoveResult
import us.dikarya.podplayer.core.data.repo.RefreshApplied
import us.dikarya.podplayer.core.data.repo.RefreshTarget
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.feed.FeedParseResult
import us.dikarya.podplayer.core.feed.FeedParser
import us.dikarya.podplayer.core.jobs.Messages
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.core.net.RedirectChain
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.redact

/**
 * Feed refresh (SPEC §8, LESSONS "Refresh").
 *
 * - One refresh at a time: a request equal to one already running joins it instead of fetching again, and
 *   different requests wait their turn, so concurrent requests from the periodic worker, the owner and the
 *   UI never fetch a feed twice at once. Inside one refresh, at most [parallelism] feeds are fetched together.
 * - Bounded and cancelable: every fetch has the gate's size and time limits and every parse the parser's.
 *   A refresh is cancelled when the last caller waiting for it is cancelled (the worker was stopped).
 * - Per-feed backoff: a failure counts against that feed only and delays its next periodic refresh
 *   ([RefreshBackoff]); the other feeds carry on. A failure never touches the stored episodes.
 * - Conditional GET with the stored validators, the owner-approved host as the only private address the feed
 *   may use (SPEC §3), and a 301/308 moves the subscription to its new address.
 */
class FeedRefresher(
    private val repositories: suspend () -> Repositories?,
    private val gate: NetGate,
    private val parser: FeedParser,
    private val log: AppLog,
    /** Where refreshes run, so a refresh outlives a caller that only joined it. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: RefreshBackoff = RefreshBackoff(),
    private val parallelism: Int = 4,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Refresher {
    private class Flight(val job: Deferred<RefreshSummary>) {
        var waiters = 0
    }

    private val flights = HashMap<RefreshRequest, Flight>()
    private val flightsLock = Mutex()
    private val runLock = Mutex()

    override suspend fun refresh(request: RefreshRequest): RefreshSummary {
        val flight = flightsLock.withLock {
            val running = flights[request]?.takeIf { it.job.isActive }
            val flight = running ?: Flight(scope.async(start = CoroutineStart.LAZY) { runLock.withLock { run(request) } })
            flights[request] = flight
            flight.waiters++
            // Started before the lock is let go: a lazy job that hasn't started reports !isActive, so an equal
            // request arriving in between would start a second flight instead of joining this one.
            flight.job.start()
            flight
        }
        try {
            return flight.job.await()
        } finally {
            withContext(NonCancellable) {
                flightsLock.withLock {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        // Nobody wants the result any more: stop fetching.
                        if (flight.job.isActive) flight.job.cancel()
                        if (flights[request] === flight) flights.remove(request)
                    }
                }
            }
        }
    }

    private suspend fun run(request: RefreshRequest): RefreshSummary {
        val repos = repositories()
        if (repos == null) {
            log.w(TAG, "The library isn't open, so nothing was refreshed")
            return RefreshSummary.Empty
        }
        val ids = when (request) {
            RefreshRequest.All -> repos.feeds.allFeedIds()
            RefreshRequest.Due -> repos.feeds.dueFeedIds(clock())
            is RefreshRequest.Feed -> listOf(request.feedId)
        }
        val permits = Semaphore(parallelism)
        val outcomes = coroutineScope {
            ids.map { id -> async { permits.withPermit { refreshOne(repos, id) } } }.awaitAll()
        }
        val summary = RefreshSummary(
            attempted = outcomes.count { it.kind != Kind.Skipped },
            updated = outcomes.count { it.kind == Kind.Updated },
            notModified = outcomes.count { it.kind == Kind.NotModified },
            failed = outcomes.count { it.kind == Kind.Failed },
            newEpisodes = outcomes.sumOf { it.newEpisodes },
        )
        log.i(TAG, "Refresh $request: $summary")
        return summary
    }

    private enum class Kind { Updated, NotModified, Failed, Skipped }

    private class FeedOutcome(val kind: Kind, val newEpisodes: Int = 0)

    private suspend fun refreshOne(repos: Repositories, feedId: Long): FeedOutcome {
        val target = repos.feeds.refreshTarget(feedId) ?: return FeedOutcome(Kind.Skipped)
        return try {
            fetchAndApply(repos, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Our bug or a storage problem, not the feed's; still never crash the refresh of the others.
            log.e(TAG, "Feed $feedId: refresh failed unexpectedly", e)
            fail(repos, target, "Something went wrong while saving the feed. The app log has details.")
        }
    }

    private suspend fun fetchAndApply(repos: Repositories, target: RefreshTarget): FeedOutcome {
        val id = target.feedId
        val url = when (val check = UrlPolicy.checkFeedUrl(target.url)) {
            is UrlCheck.Allowed -> check.url
            is UrlCheck.Rejected -> return fail(repos, target, "The feed's address isn't one this app can use (${check.problem.name}).")
        }
        val policy = target.ownerApprovedHost?.let { AddressPolicy.approvingHosts(it) } ?: AddressPolicy.Default
        val validators = target.validators.takeUnless { it.isEmpty }
        return when (val result = gate.fetch(url, BodyLimit.FEED, policy, validators)) {
            is FetchResult.Failure -> fail(repos, target, Messages.net(result.error))
            is FetchResult.NotModified -> {
                val moved = applyMove(repos, id, url, result.redirects)
                repos.feeds.recordRefreshSuccess(id, validators = null, at = clock())
                repos.logs.addRefresh(id, RefreshOutcome.NotModified, sentence("Not changed since the last refresh.", moved))
                FeedOutcome(Kind.NotModified)
            }
            is FetchResult.Success -> {
                val parsed = withContext(parseDispatcher) { parser.parse(result.body, result.contentType) }
                when (parsed) {
                    is FeedParseResult.Failure -> fail(repos, target, Messages.parse(parsed.problem))
                    is FeedParseResult.Success -> when (val applied = repos.feeds.applyRefresh(id, parsed.feed)) {
                        RefreshApplied.FeedGone -> FeedOutcome(Kind.Skipped)
                        is RefreshApplied.Applied -> {
                            val moved = applyMove(repos, id, url, result.redirects)
                            repos.feeds.recordRefreshSuccess(id, result.validators, clock())
                            val details = buildList {
                                if (applied.noLongerInFeed > 0) add("${applied.noLongerInFeed} no longer in the feed")
                                if (applied.backInFeed > 0) add("${applied.backInFeed} back in the feed")
                                if (applied.skippedWithoutEnclosure > 0) add("${applied.skippedWithoutEnclosure} items without audio skipped")
                                addAll(Messages.warnings(parsed.warnings))
                            }
                            val summary = "${applied.inserted} new, ${applied.updated} updated" +
                                details.joinToString("") { "; $it" } + "."
                            repos.logs.addRefresh(id, RefreshOutcome.Updated, sentence(summary, moved), applied.inserted, applied.updated)
                            log.i(TAG, "Feed $id: ${applied.inserted} new, ${applied.updated} updated")
                            FeedOutcome(Kind.Updated, applied.inserted)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fail(repos: Repositories, target: RefreshTarget, message: String): FeedOutcome {
        val now = clock()
        val failures = repos.feeds.recordRefreshFailure(target.feedId, message, now) { failures -> now + backoff.delayMs(failures) }
        repos.logs.addRefresh(target.feedId, RefreshOutcome.Failed, message)
        log.w(TAG, "Feed ${target.feedId}: failure $failures in a row: $message")
        return FeedOutcome(Kind.Failed)
    }

    /**
     * SPEC §3: a 301/308 moves the stored URL. Credentials written into the URL stay with it while the feed
     * stays on the same host (the gate dropped them for any other host anyway). Returns a sentence for the log.
     */
    private suspend fun applyMove(repos: Repositories, feedId: Long, requested: HttpUrl, chain: RedirectChain): String? {
        var moved = chain.permanentUrl ?: return null
        if (moved.host == requested.host && moved.username.isEmpty() && requested.username.isNotEmpty()) {
            moved = moved.newBuilder().username(requested.username).password(requested.password).build()
        }
        return when (val result = repos.feeds.moveUrl(feedId, moved.toString())) {
            MoveResult.Moved -> {
                log.i(TAG, "Feed $feedId moved permanently to ${redact(moved)}")
                "The feed moved permanently to ${redact(moved)}; the subscription now uses that address."
            }
            is MoveResult.AlreadySubscribed ->
                "The feed says it moved to an address you're already subscribed to, so this subscription keeps its old address."
            MoveResult.Refused -> "The feed says it moved to an address this app can't use, so it keeps its old address."
            MoveResult.Unchanged, MoveResult.FeedGone -> null
        }
    }

    private fun sentence(first: String, second: String?) = if (second == null) first else "$first $second"

    private companion object {
        const val TAG = "Refresh"
    }
}
