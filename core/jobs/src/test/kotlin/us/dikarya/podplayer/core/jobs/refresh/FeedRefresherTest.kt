/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.refresh

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.jobs.FEED_HOST
import us.dikarya.podplayer.core.jobs.HOUR
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.MINUTE
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.xmlResponse

/** SPEC §8 and LESSONS "Refresh": one refresh at a time, bounded and cancelable, per-feed backoff, a log. */
@RunWith(AndroidJUnit4::class)
class FeedRefresherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var env: TestEnv

    @Before
    fun setUp() {
        env = TestEnv(tmp.root)
    }

    @After
    fun tearDown() = env.close()

    private fun items(n: Int, prefix: String = "ep") =
        (1..n).map { Item("guid-$prefix-$it", "Episode $it", env.url("/$prefix$it.mp3").toString()) }

    private fun refresh(request: RefreshRequest): RefreshSummary = runBlocking { env.refresher.refresh(request) }

    private fun episodeCount(feedId: Long) = runBlocking { env.repos().episodes.forFeed(feedId).first().size }

    private fun feed(feedId: Long) = runBlocking { env.repos().feeds.get(feedId)!! }

    private fun target(feedId: Long) = runBlocking { env.repos().feeds.refreshTarget(feedId)!! }

    private fun refreshLog(feedId: Long) = runBlocking { env.repos().logs.refreshLog(feedId, 50).first() }

    @Test
    fun concurrentRefreshRequestsCollapseIntoOne() {
        val a = env.subscribe("/a.xml")
        val b = env.subscribe("/b.xml")
        val slow = { xml: String -> MockResponse.Builder().headersDelay(300, TimeUnit.MILLISECONDS).body(xml).build() }
        env.routes["/a.xml"] = { slow(feedXml(items(2, "a"))) }
        env.routes["/b.xml"] = { slow(feedXml(items(3, "b"))) }

        val summaries = runBlocking {
            (1..10).map { async(Dispatchers.IO) { env.refresher.refresh(RefreshRequest.All) } }.awaitAll()
        }

        assertEquals("one request per feed for ten refresh requests", 1, env.routes.count("/a.xml"))
        assertEquals(1, env.routes.count("/b.xml"))
        assertTrue("every caller gets the one result", summaries.all { it == summaries.first() })
        assertEquals(2, summaries.first().updated)
        assertEquals(5, summaries.first().newEpisodes)
        assertEquals(2, episodeCount(a))
        assertEquals(3, episodeCount(b))
    }

    @Test
    fun aRefreshRequestedAfterTheLastOneFinishedRunsAgain() {
        env.subscribe("/a.xml")
        env.routes["/a.xml"] = { xmlResponse(feedXml(items(1))) }
        refresh(RefreshRequest.All)
        refresh(RefreshRequest.All)
        assertEquals(2, env.routes.count("/a.xml"))
    }

    @Test
    fun aFailingFeedBacksOffWithoutBlockingOthers() {
        val bad = env.subscribe("/bad.xml")
        val good = env.subscribe("/good.xml")
        env.routes["/bad.xml"] = { MockResponse.Builder().code(500).build() }
        env.routes["/good.xml"] = { xmlResponse(feedXml(items(2))) }

        val first = refresh(RefreshRequest.All)
        assertEquals(1, first.failed)
        assertEquals(1, first.updated)
        assertEquals(2, episodeCount(good))
        assertEquals(1, target(bad).consecutiveFailures)
        assertEquals(env.clock.now + 30 * MINUTE, target(bad).nextRefreshAt)
        val failure = refreshLog(bad).first()
        assertEquals(RefreshOutcome.Failed, failure.outcome)
        assertTrue(failure.message, "HTTP 500" in failure.message!!)
        assertTrue(feed(bad).lastRefreshError!!.isNotBlank())

        // The periodic refresh skips the feed until its time comes, but still refreshes the others.
        refresh(RefreshRequest.Due)
        assertEquals(1, env.routes.count("/bad.xml"))
        assertEquals(2, env.routes.count("/good.xml"))

        env.clock.advance(30 * MINUTE)
        refresh(RefreshRequest.Due)
        assertEquals(2, env.routes.count("/bad.xml"))
        assertEquals("the wait doubles", env.clock.now + HOUR, target(bad).nextRefreshAt)

        env.routes["/bad.xml"] = { xmlResponse(feedXml(items(1, "x"))) }
        env.clock.advance(HOUR)
        refresh(RefreshRequest.Due)
        assertEquals(0, target(bad).consecutiveFailures)
        assertNull(target(bad).nextRefreshAt)
        assertEquals(1, episodeCount(bad))
    }

    @Test
    fun theOwnerCanRefreshABackedOffFeedAtOnce() {
        val bad = env.subscribe("/bad.xml")
        env.routes["/bad.xml"] = { MockResponse.Builder().code(503).build() }
        refresh(RefreshRequest.All)
        refresh(RefreshRequest.Feed(bad))
        assertEquals(2, env.routes.count("/bad.xml"))
        assertEquals(2, target(bad).consecutiveFailures)
    }

    @Test
    fun backoffDoublesFromHalfAnHourAndStopsAtADay() {
        val backoff = RefreshBackoff()
        assertEquals(30 * MINUTE, backoff.delayMs(1))
        assertEquals(HOUR, backoff.delayMs(2))
        assertEquals(2 * HOUR, backoff.delayMs(3))
        assertEquals(16 * HOUR, backoff.delayMs(6))
        assertEquals(24 * HOUR, backoff.delayMs(7))
        assertEquals(24 * HOUR, backoff.delayMs(1_000))
        assertEquals(30 * MINUTE, backoff.delayMs(0))
    }

    @Test
    fun conditionalGetSendsTheValidatorsAndA304ChangesNothing() {
        val id = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = { request ->
            if (request.headers["If-None-Match"] == "\"e1\"") {
                MockResponse.Builder().code(304).build()
            } else {
                MockResponse.Builder().addHeader("ETag", "\"e1\"").addHeader("Last-Modified", "Sat, 07 Nov 2026 10:00:00 GMT")
                    .body(feedXml(items(2))).build()
            }
        }
        refresh(RefreshRequest.Feed(id))
        val summary = refresh(RefreshRequest.Feed(id))
        assertEquals(1, summary.notModified)
        val second = env.routes.requestsTo("/feed.xml")[1]
        assertEquals("\"e1\"", second.headers["If-None-Match"])
        assertEquals("Sat, 07 Nov 2026 10:00:00 GMT", second.headers["If-Modified-Since"])
        assertEquals(RefreshOutcome.NotModified, refreshLog(id).first().outcome)
        assertEquals(2, episodeCount(id))
        assertEquals("\"e1\"", target(id).validators.etag)
    }

    @Test
    fun aPermanentRedirectMovesTheFeedAndATemporaryOneDoesNot() {
        val id = env.subscribe("/old.xml")
        val newUrl = env.url("/new.xml").newBuilder().query("token=SECRETTOKEN").build().toString()
        env.routes["/old.xml"] = { MockResponse.Builder().code(301).addHeader("Location", newUrl).build() }
        env.routes["/new.xml"] = { xmlResponse(feedXml(items(1))) }
        refresh(RefreshRequest.Feed(id))
        assertEquals(newUrl, feed(id).url)
        refresh(RefreshRequest.Feed(id))
        assertEquals("the next refresh goes straight to the new address", 1, env.routes.count("/old.xml"))

        val other = env.subscribe("/temp.xml")
        val otherUrl = feed(other).url
        env.routes["/temp.xml"] = { MockResponse.Builder().code(302).addHeader("Location", "/elsewhere.xml").build() }
        env.routes["/elsewhere.xml"] = { xmlResponse(feedXml(items(1, "t"))) }
        refresh(RefreshRequest.Feed(other))
        assertEquals(otherUrl, feed(other).url)
        assertEquals(1, episodeCount(other))
    }

    @Test
    fun aPermanentRedirectOntoAnotherSubscriptionKeepsTheOldUrl() {
        val a = env.subscribe("/a.xml")
        val b = env.subscribe("/b.xml")
        val aUrl = feed(a).url
        env.routes["/a.xml"] = { MockResponse.Builder().code(308).addHeader("Location", feed(b).url).build() }
        env.routes["/b.xml"] = { xmlResponse(feedXml(items(1))) }
        refresh(RefreshRequest.Feed(a))
        assertEquals(aUrl, feed(a).url)
        assertTrue(refreshLog(a).first().message!!.contains("already subscribed"))
    }

    @Test
    fun credentialsInTheUrlAreSentAndSurviveAMoveOnTheSameHost() {
        val id = runBlocking {
            val url = env.url("/private.xml").newBuilder().username("me").password("pw").build().toString()
            (env.repos().feeds.subscribe(url, ownerTyped = true) as SubscribeResult.Subscribed).feedId
        }
        env.routes["/private.xml"] = { MockResponse.Builder().code(301).addHeader("Location", "/moved.xml").build() }
        env.routes["/moved.xml"] = { xmlResponse(feedXml(items(1))) }
        refresh(RefreshRequest.Feed(id))
        assertEquals("Basic bWU6cHc=", env.routes.requestsTo("/moved.xml").single().headers["Authorization"])
        val moved = feed(id).url.toHttpUrl()
        assertEquals("/moved.xml", moved.encodedPath)
        assertEquals("me", moved.username)
        assertEquals("pw", moved.password)
    }

    @Test
    fun aBrokenResponseKeepsTheLastGoodEpisodes() {
        val id = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = { xmlResponse(feedXml(items(3))) }
        refresh(RefreshRequest.Feed(id))
        assertEquals(3, episodeCount(id))

        env.routes["/feed.xml"] = {
            MockResponse.Builder().addHeader("Content-Type", "text/html").body("<!DOCTYPE html><html><body>Moved!</body></html>").build()
        }
        val summary = refresh(RefreshRequest.Feed(id))
        assertEquals(1, summary.failed)
        assertEquals(3, episodeCount(id))
        assertTrue(refreshLog(id).first().message!!.contains("web page"))

        env.routes["/feed.xml"] = { xmlResponse("<rss><channel><item><title>cut off") }
        refresh(RefreshRequest.Feed(id))
        assertEquals(3, episodeCount(id))
        assertEquals(2, target(id).consecutiveFailures)
        assertTrue("validators aren't kept from a response that couldn't be used", target(id).validators.isEmpty)
    }

    @Test
    fun theLogSaysWhatChanged() {
        val id = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = { xmlResponse(feedXml(items(2))) }
        refresh(RefreshRequest.Feed(id))
        val first = refreshLog(id).first()
        assertEquals(RefreshOutcome.Updated, first.outcome)
        assertEquals(2, first.newEpisodes)

        val edited = items(2).mapIndexed { i, item -> if (i == 0) item.copy(title = "Episode 1 (fixed)") else item }
        env.routes["/feed.xml"] = { xmlResponse(feedXml(edited)) }
        refresh(RefreshRequest.Feed(id))
        val second = refreshLog(id).first()
        assertEquals(0, second.newEpisodes)
        assertEquals(1, second.updatedEpisodes)
    }

    @Test
    fun noTokenReachesAnyLog() {
        val id = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = { MockResponse.Builder().code(404).build() }
        refresh(RefreshRequest.Feed(id))
        env.log.flush()
        val texts = refreshLog(id).mapNotNull { it.message } + listOfNotNull(feed(id).lastRefreshError) +
            File(tmp.root, "logs").walk().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue(texts.isNotEmpty())
        for (text in texts) assertFalse(text, "SECRETTOKEN" in text)
    }

    @Test
    fun aFeedNobodyTypedMayNotReachAPrivateAddress() {
        val id = env.subscribe("/feed.xml", ownerTyped = false)
        env.routes["/feed.xml"] = { xmlResponse(feedXml(items(1))) }
        val summary = refresh(RefreshRequest.Feed(id))
        assertEquals(1, summary.failed)
        assertEquals("refused before any request was sent", 0, env.routes.count("/feed.xml"))
        assertTrue(refreshLog(id).first().message!!.contains("private network"))
        assertEquals(FEED_HOST, feed(id).url.toHttpUrl().host)
    }

    @Test
    fun cancellingStopsASlowRefreshWithoutCountingAFailure() = runBlocking {
        val id = env.subscribe("/slow.xml")
        env.routes["/slow.xml"] = { MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).body(feedXml(items(1))).build() }
        val job = launch(Dispatchers.IO) { env.refresher.refresh(RefreshRequest.All) }
        delay(300)
        withTimeout(3_000) {
            job.cancel()
            job.join()
        }
        assertEquals(0, target(id).consecutiveFailures)
        assertNull(feed(id).lastRefreshSuccessAt)
    }

    @Test
    fun anUnknownFeedIsNotAnError() {
        assertEquals(0, refresh(RefreshRequest.Feed(9_999)).attempted)
    }
}
