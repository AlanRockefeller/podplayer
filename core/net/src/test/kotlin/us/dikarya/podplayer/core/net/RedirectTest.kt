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

/** SPEC §3: redirects are followed by hand, at most 5 hops, and 301/308 are told apart from 302/307. */
class RedirectTest {
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

    private fun fetch(path: String = "/feed", policy: AddressPolicy = approvedFeedAndCdn): FetchResult = runBlocking {
        gate.fetch(server.urlOn(FEED_HOST, path), BodyLimit.FEED, policy)
    }

    @Test
    fun redirectLoopStopsAtFiveHops() {
        repeat(10) { server.enqueue(redirect(302, "/loop")) }
        val result = fetch()
        assertEquals(NetError.TooManyRedirects(5), (result as FetchResult.Failure).error)
        assertEquals("the first request plus five followed hops", 6, server.requestCount)
    }

    @Test
    fun fiveHopsAreStillFollowed() {
        repeat(5) { i -> server.enqueue(redirect(302, "/hop$i")) }
        server.enqueue(ok("<rss/>"))
        val result = fetch() as FetchResult.Success
        assertEquals(5, result.redirects.hops.size)
        assertEquals("/hop4", result.redirects.finalUrl.encodedPath)
    }

    @Test
    fun permanentRedirectIsReported() {
        for (code in listOf(301, 308)) {
            server.enqueue(redirect(code, server.urlOn(CDN_HOST, "/new-feed").toString()))
            server.enqueue(ok("<rss/>"))
            val chain = (fetch() as FetchResult.Success).redirects
            assertTrue("$code", chain.finalHopPermanent)
            assertEquals("$code", server.urlOn(CDN_HOST, "/new-feed"), chain.permanentUrl)
        }
    }

    @Test
    fun temporaryRedirectIsReported() {
        for (code in listOf(302, 303, 307)) {
            server.enqueue(redirect(code, "/tmp"))
            server.enqueue(ok("<rss/>"))
            val chain = (fetch() as FetchResult.Success).redirects
            assertFalse("$code", chain.finalHopPermanent)
            assertNull("$code: a temporary hop never changes the stored feed URL", chain.permanentUrl)
            assertEquals("/tmp", chain.finalUrl.encodedPath)
        }
    }

    @Test
    fun permanentUrlStopsAtTheFirstTemporaryHop() {
        // A moved permanently to B; B temporarily serves C. The new feed address is B, not C.
        server.enqueue(redirect(301, "/b"))
        server.enqueue(redirect(302, "/c"))
        server.enqueue(ok("<rss/>"))
        val chain = (fetch("/a") as FetchResult.Success).redirects
        assertEquals("/b", chain.permanentUrl?.encodedPath)
        assertFalse(chain.finalHopPermanent)
        assertEquals("/c", chain.finalUrl.encodedPath)
    }

    @Test
    fun chainedPermanentRedirectsReachTheEnd() {
        server.enqueue(redirect(301, "/b"))
        server.enqueue(redirect(308, "/c"))
        server.enqueue(ok("<rss/>"))
        val chain = (fetch("/a") as FetchResult.Success).redirects
        assertEquals("/c", chain.permanentUrl?.encodedPath)
        assertTrue(chain.finalHopPermanent)
    }

    @Test
    fun noRedirectMeansAnEmptyChain() {
        server.enqueue(ok())
        val chain = (fetch() as FetchResult.Success).redirects
        assertTrue(chain.hops.isEmpty())
        assertNull(chain.permanentUrl)
        assertFalse(chain.finalHopPermanent)
    }

    @Test
    fun relativeLocationsResolveAgainstTheCurrentHop() {
        server.enqueue(redirect(302, "../other/feed.xml"))
        server.enqueue(ok())
        val chain = (fetch("/shows/one/feed") as FetchResult.Success).redirects
        assertEquals("/shows/other/feed.xml", chain.finalUrl.encodedPath)
    }

    @Test
    fun redirectWithoutLocationIsAnError() {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(302).build())
        assertTrue((fetch() as FetchResult.Failure).error is NetError.BadRedirect)
    }

    @Test
    fun redirectToANonHttpSchemeIsRefused() {
        for (location in listOf("file:///etc/passwd", "ftp://example.com/feed", "javascript:alert(1)", "content://x/y")) {
            server.enqueue(redirect(302, location))
            assertTrue(location, (fetch() as FetchResult.Failure).error is NetError.BadRedirect)
        }
    }

    @Test
    fun credentialsAreDroppedWhenTheHostChanges() {
        server.enqueue(redirect(302, server.urlOn(CDN_HOST, "/a.mp3").toString()))
        server.enqueue(ok())
        val request = Request.Builder().url(server.urlOn(FEED_HOST))
            .header("Authorization", "Basic c2VjcmV0")
            .header("Cookie", "session=1")
            .build()
        gate.callFactory(approvedFeedAndCdn).newCall(request).execute().close()
        assertEquals("Basic c2VjcmV0", server.takeRequest().headers["Authorization"])
        val second = server.takeRequest()
        assertEquals(CDN_HOST, second.url.host)
        assertNull(second.headers["Authorization"])
        assertNull(second.headers["Cookie"])
    }

    @Test
    fun credentialsAreKeptOnTheSameHost() {
        server.enqueue(redirect(302, "/next"))
        server.enqueue(ok())
        val request = Request.Builder().url(server.urlOn(FEED_HOST)).header("Authorization", "Basic c2VjcmV0").build()
        gate.callFactory(approvedFeedAndCdn).newCall(request).execute().close()
        server.takeRequest()
        assertEquals("Basic c2VjcmV0", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun rangeHeadersSurviveARedirect() {
        server.enqueue(redirect(302, "/media.mp3"))
        server.enqueue(ok())
        val request = Request.Builder().url(server.urlOn(FEED_HOST)).header("Range", "bytes=100-").build()
        gate.callFactory(approvedFeedAndCdn).newCall(request).execute().close()
        server.takeRequest()
        assertEquals("bytes=100-", server.takeRequest().headers["Range"])
    }

    @Test
    fun chainDoesNotPrintSecrets() {
        server.enqueue(redirect(301, server.urlOn(CDN_HOST, "/f?token=SECRET").toString()))
        server.enqueue(ok())
        val chain = (fetch("/f?key=SECRET") as FetchResult.Success).redirects
        assertFalse(chain.toString(), "SECRET" in chain.toString())
        assertFalse("SECRET" in chain.hops.single().toString())
    }
}
