/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** SPEC §3: one client, its timeouts, its user agent, and nothing that bypasses the gate. */
class ClientConfigTest {
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

    @Test
    fun clientSettings() {
        val client = gate.client(AddressPolicy.Default)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals("no total-time limit on downloads", 0, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals("a system proxy would bypass the address filter", Proxy.NO_PROXY, client.proxy)
        assertNull(client.cache)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.dns is GatedDns)
    }

    @Test
    fun clientsForDifferentPoliciesShareThePoolAndDispatcher() {
        val a: OkHttpClient = gate.client(AddressPolicy.Default)
        val b: OkHttpClient = gate.client(approvedFeedHost)
        assertSame(a.connectionPool, b.connectionPool)
        assertSame(a.dispatcher, b.dispatcher)
        assertSame("clients are reused per policy", b, gate.client(AddressPolicy.approvingHosts(FEED_HOST)))
    }

    @Test
    fun callFactoryIsNotTheClientItself() {
        // Other modules must not be able to cast it back and call newBuilder() to strip the gate.
        assertFalse(gate.callFactory(AddressPolicy.Default) is OkHttpClient)
    }

    @Test
    fun userAgentIsExactlyOurs() = runBlocking {
        server.enqueue(ok())
        gate.fetch(server.urlOn(FEED_HOST), BodyLimit.FEED, approvedFeedHost)
        val request = server.takeRequest()
        assertEquals("Podplayer/1.2.3 (Android)", request.headers["User-Agent"])
        assertNull(request.headers["Referer"])
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun userAgentReplacesOneSetByACaller() {
        // ExoPlayer may set its own; ours wins.
        server.enqueue(ok())
        val request = Request.Builder().url(server.urlOn(FEED_HOST)).header("User-Agent", "ExoPlayerLib/1.0").build()
        gate.callFactory(approvedFeedHost).newCall(request).execute().close()
        assertEquals("Podplayer/1.2.3 (Android)", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun userAgentVersionIsSanitized() {
        assertEquals("Podplayer/0.1.0-debug (Android)", NetConfig(appVersion = "0.1.0-debug").userAgent)
        assertEquals("Podplayer/1.0bad (Android)", NetConfig(appVersion = "1.0\r\nbad").userAgent)
        assertEquals("Podplayer/unknown (Android)", NetConfig(appVersion = "").userAgent)
    }

    @Test
    fun defaultTimeoutsMatchTheSpec() {
        val config = NetConfig(appVersion = "1")
        assertEquals(15_000, config.connectTimeout.inWholeMilliseconds)
        assertEquals(30_000, config.readTimeout.inWholeMilliseconds)
        assertEquals(5, config.maxRedirects)
    }

    @Test
    fun slowBodiesDoNotHoldUpOtherRequestsToTheSameHost() = runBlocking {
        // OkHttp runs at most five calls per host at once and counts a call as running until its callback
        // returns. Bodies are read outside the callback, so six slow downloads from one tracker host can't
        // queue a feed or artwork fetch to that host behind them.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/slow") {
                    okBytes(testBytes(20 * 1024)) { throttleBody(1024, 500, TimeUnit.MILLISECONDS) }
                } else {
                    ok("quick")
                }
        }
        val slow = List(6) {
            async(Dispatchers.Default) { gate.fetch(server.urlOn(FEED_HOST, "/slow"), BodyLimit.FEED, approvedFeedHost) }
        }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (server.requestCount < 6) {
                if (System.nanoTime() > deadline) fail("only ${server.requestCount} of 6 slow requests reached the server")
                delay(10)
            }
            val quick = withTimeout(3.seconds) { gate.fetch(server.urlOn(FEED_HOST, "/quick"), BodyLimit.FEED, approvedFeedHost) }
            assertTrue("$quick", quick is FetchResult.Success)
        } finally {
            slow.forEach { it.cancel() }
        }
    }
}
