/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Private feeds written as `https://user:pass@host/feed` (PROGRESS "Later", S5). OkHttp never sends URL
 * userinfo, so the gate turns it into HTTP Basic auth, and only for the host it was written for.
 */
class UserinfoAuthTest {
    private lateinit var server: MockWebServer
    private val gate = testGate()

    @Before
    fun setUp() {
        server = startServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun withUserinfo(host: String, path: String = "/feed") =
        server.urlOn(host, path).newBuilder().username("listener").password("s3cret pass").build()

    @Test
    fun userinfoBecomesBasicAuth() = runBlocking {
        server.enqueue(ok("<rss/>"))
        val result = gate.fetch(withUserinfo(FEED_HOST), BodyLimit.FEED, approvedFeedAndCdn)
        assertTrue(result is FetchResult.Success)
        val recorded = server.takeRequest()
        assertEquals("Basic bGlzdGVuZXI6czNjcmV0IHBhc3M=", recorded.headers["Authorization"])
        assertEquals("/feed", recorded.url.encodedPath)
        assertEquals("", recorded.url.username)
    }

    @Test
    fun authSurvivesARedirectOnTheSameHost() = runBlocking {
        server.enqueue(redirect(302, "/elsewhere"))
        server.enqueue(ok("<rss/>"))
        gate.fetch(withUserinfo(FEED_HOST), BodyLimit.FEED, approvedFeedAndCdn)
        server.takeRequest()
        assertEquals("Basic bGlzdGVuZXI6czNjcmV0IHBhc3M=", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun authIsDroppedWhenARedirectChangesHost() = runBlocking {
        server.enqueue(redirect(302, server.urlOn(CDN_HOST, "/feed").toString()))
        server.enqueue(ok("<rss/>"))
        gate.fetch(withUserinfo(FEED_HOST), BodyLimit.FEED, approvedFeedAndCdn)
        assertTrue(server.takeRequest().headers["Authorization"] != null)
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun anExplicitAuthorizationHeaderWins() = runBlocking {
        server.enqueue(ok("<rss/>"))
        val request = Request.Builder().url(withUserinfo(FEED_HOST)).header("Authorization", "Bearer abc").build()
        gate.callFactory(approvedFeedAndCdn).newCall(request).execute().close()
        assertEquals("Bearer abc", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun theRedirectChainStillStartsAtTheOwnersUrl() = runBlocking {
        server.enqueue(ok("<rss/>"))
        val url = withUserinfo(FEED_HOST)
        val result = gate.fetch(url, BodyLimit.FEED, approvedFeedAndCdn) as FetchResult.Success
        assertEquals(url, result.redirects.start)
        assertFalse("no secret in the chain's description", "s3cret" in result.redirects.toString())
    }

    @Test
    fun aUrlWithoutUserinfoSendsNoAuthorization() = runBlocking {
        server.enqueue(ok("<rss/>"))
        gate.fetch(server.urlOn(FEED_HOST, "/feed"), BodyLimit.FEED, approvedFeedAndCdn)
        assertNull(server.takeRequest().headers["Authorization"])
    }
}
