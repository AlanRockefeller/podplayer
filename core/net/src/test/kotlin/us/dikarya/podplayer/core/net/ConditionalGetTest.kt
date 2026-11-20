/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SPEC §3: feeds are fetched with If-None-Match / If-Modified-Since from the last good response. */
class ConditionalGetTest {
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

    private fun fetch(validators: Validators?): FetchResult = runBlocking {
        gate.fetch(server.urlOn(FEED_HOST, "/feed"), BodyLimit.FEED, approvedFeedHost, validators)
    }

    @Test
    fun firstFetchSendsNoConditionsAndReturnsValidators() {
        server.enqueue(
            MockResponse.Builder().body("<rss/>")
                .addHeader("ETag", "\"v1\"")
                .addHeader("Last-Modified", "Sun, 08 Nov 2026 10:00:00 GMT")
                .build(),
        )
        val result = fetch(null) as FetchResult.Success
        assertEquals("\"v1\"", result.validators.etag)
        assertEquals("Sun, 08 Nov 2026 10:00:00 GMT", result.validators.lastModified)
        val request = server.takeRequest()
        assertNull(request.headers["If-None-Match"])
        assertNull(request.headers["If-Modified-Since"])
    }

    @Test
    fun storedValidatorsAreSent() {
        server.enqueue(MockResponse.Builder().code(304).build())
        val result = fetch(Validators.of(etag = "\"v1\"", lastModified = "Sun, 08 Nov 2026 10:00:00 GMT"))
        assertTrue(result is FetchResult.NotModified)
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.headers["If-None-Match"])
        assertEquals("Sun, 08 Nov 2026 10:00:00 GMT", request.headers["If-Modified-Since"])
    }

    @Test
    fun weakEtagsWorkForConditionalGet() {
        server.enqueue(MockResponse.Builder().code(304).build())
        fetch(Validators.of(etag = "W/\"v1\"", lastModified = null))
        assertEquals("W/\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun modifiedFeedReturnsNewValidators() {
        server.enqueue(MockResponse.Builder().body("<rss>2</rss>").addHeader("ETag", "\"v2\"").build())
        val result = fetch(Validators.of(etag = "\"v1\"", lastModified = null)) as FetchResult.Success
        assertEquals("\"v2\"", result.validators.etag)
        assertNull(result.validators.lastModified)
        assertEquals("<rss>2</rss>", result.body.decodeToString())
    }

    @Test
    fun notModifiedAfterARedirectReportsTheChain() {
        server.enqueue(redirect(301, "/new"))
        server.enqueue(MockResponse.Builder().code(304).build())
        val result = fetch(Validators.of(etag = "\"v1\"", lastModified = null)) as FetchResult.NotModified
        assertEquals("/new", result.redirects.permanentUrl?.encodedPath)
        server.takeRequest()
        assertEquals("conditions follow the redirect", "\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun hostileValidatorsAreDroppedInsteadOfCrashingTheNextRequest() {
        // OkHttp refuses to send header values with control or non-ASCII characters.
        server.enqueue(
            MockResponse.Builder().body("<rss/>")
                .addHeaderLenient("ETag", "\"café\"")
                .addHeaderLenient("Last-Modified", "x".repeat(5000))
                .build(),
        )
        val result = fetch(null) as FetchResult.Success
        assertNull(result.validators.etag)
        assertNull(result.validators.lastModified)
    }

    @Test
    fun storedValidatorsAreSanitizedToo() {
        val validators = Validators.of(etag = "\"a\"\r\nX-Injected: 1", lastModified = "Sun, 08 Nov 2026 10:00:00 GMT\u0000")
        assertNull(validators.etag)
        assertNull(validators.lastModified)
        assertTrue(validators.isEmpty)
        server.enqueue(ok())
        assertTrue(fetch(validators) is FetchResult.Success)
        assertNull(server.takeRequest().headers["X-Injected"])
    }

    @Test
    fun onlyStrongEtagsOrDatesAreUsedForIfRange() {
        assertEquals("\"v1\"", Validators.of(etag = "\"v1\"", lastModified = "d").ifRangeValue)
        assertEquals("d", Validators.of(etag = "W/\"v1\"", lastModified = "d").ifRangeValue)
        assertNull(Validators.of(etag = "W/\"v1\"", lastModified = null).ifRangeValue)
        assertNull(Validators.of(etag = null, lastModified = null).ifRangeValue)
    }
}
