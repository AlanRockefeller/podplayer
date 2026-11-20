/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SPEC §3 size limits, and CLAUDE.md: every read has byte and time limits; on breach, reject. */
class BodyLimitTest {
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

    private fun fetch(limit: BodyLimit): FetchResult = runBlocking {
        gate.fetch(server.urlOn(FEED_HOST, "/feed"), limit, approvedFeedHost)
    }

    @Test
    fun specLimits() {
        val mb = 1024L * 1024
        assertEquals(20 * mb, BodyLimit.FEED.maxBytes)
        assertEquals(20 * mb, BodyLimit.OPML.maxBytes)
        assertEquals(5 * mb, BodyLimit.CHAPTERS.maxBytes)
        assertEquals(5 * mb, BodyLimit.TRANSCRIPT.maxBytes)
        assertEquals(10 * mb, BodyLimit.IMAGE.maxBytes)
        assertEquals(2048 * mb, NetLimits.MEDIA_MAX_BYTES)
        assertEquals(2.minutes, BodyLimit.FEED.maxDuration)
    }

    @Test
    fun bodyAtTheLimitIsAccepted() {
        val bytes = testBytes(4096)
        server.enqueue(okBytes(bytes))
        val result = fetch(BodyLimit(maxBytes = 4096, maxDuration = 10.seconds)) as FetchResult.Success
        assertArrayEquals(bytes, result.body)
    }

    @Test
    fun declaredLengthOverTheLimitIsRefusedBeforeReading() {
        server.enqueue(okBytes(testBytes(4097)))
        val result = fetch(BodyLimit(maxBytes = 4096, maxDuration = 10.seconds))
        assertEquals(NetError.TooLarge(4096), (result as FetchResult.Failure).error)
    }

    @Test
    fun chunkedBodyOverTheLimitIsRefused() {
        // No Content-Length: the limit is enforced while reading.
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(testBytes(50_000)), 1000).build())
        val result = fetch(BodyLimit(maxBytes = 10_000, maxDuration = 10.seconds))
        assertEquals(NetError.TooLarge(10_000), (result as FetchResult.Failure).error)
    }

    @Test
    fun gzipBombIsRefusedOnDecompressedBytes() {
        // 8 MB of zeros compresses to a few kilobytes; the limit counts what comes out.
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.write(ByteArray(8 * 1024 * 1024)) }
        assertTrue(compressed.size < 100_000)
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(compressed).build(),
        )
        val result = fetch(BodyLimit(maxBytes = 1024 * 1024, maxDuration = 10.seconds))
        assertEquals(NetError.TooLarge(1024 * 1024), (result as FetchResult.Failure).error)
    }

    @Test
    fun smallGzipBodiesAreDecompressed() {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("<rss/>") }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(compressed).build())
        val result = fetch(BodyLimit.FEED) as FetchResult.Success
        assertEquals("<rss/>", result.body.decodeToString())
    }

    @Test
    fun slowDripPastTheTotalTimeLimitIsRefused() {
        // 1 byte every 50 ms never trips the 30 s read timeout, but the total limit catches it.
        server.enqueue(okBytes(testBytes(200)) { throttleBody(1, 50, TimeUnit.MILLISECONDS) })
        val result = fetch(BodyLimit(maxBytes = 1024, maxDuration = 400.milliseconds))
        assertEquals(NetError.TimedOut, (result as FetchResult.Failure).error)
    }

    @Test
    fun readTimeoutIsReported() {
        val quickGate = testGate(NetConfig(appVersion = "1.2.3", readTimeout = 300.milliseconds))
        server.enqueue(ok("late").newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        val result = runBlocking { quickGate.fetch(server.urlOn(FEED_HOST), BodyLimit.FEED, approvedFeedHost) }
        assertEquals(NetError.TimedOut, (result as FetchResult.Failure).error)
    }

    @Test
    fun httpErrorsAreTyped() {
        server.enqueue(MockResponse.Builder().code(404).body("not here").build())
        assertEquals(NetError.HttpStatus(404), (fetch(BodyLimit.FEED) as FetchResult.Failure).error)
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(NetError.HttpStatus(503), (fetch(BodyLimit.FEED) as FetchResult.Failure).error)
    }

    @Test
    fun droppedConnectionIsANetworkError() {
        server.enqueue(okBytes(testBytes(10_000)) { onResponseBody(mockwebserver3.SocketEffect.CloseSocket()) })
        val error = (fetch(BodyLimit.FEED) as FetchResult.Failure).error
        assertTrue("$error", error is NetError.Network)
    }

    @Test
    fun networkErrorDetailsNeverCarryQueryStrings() = runBlocking {
        server.enqueue(okBytes(testBytes(10_000)) { onResponseBody(mockwebserver3.SocketEffect.CloseSocket()) })
        val result = gate.fetch(server.urlOn(FEED_HOST, "/feed?token=SECRET"), BodyLimit.FEED, approvedFeedHost)
        val error = (result as FetchResult.Failure).error
        assertTrue("$error", "SECRET" !in error.toString())
    }

    @Test
    fun unknownHostIsANetworkError() = runBlocking {
        val result = gate.fetch(
            okhttp3.HttpUrl.Builder().scheme("https").host("nowhere.example.test").build(),
            BodyLimit.FEED,
        )
        assertTrue((result as FetchResult.Failure).error is NetError.Network)
    }
}
