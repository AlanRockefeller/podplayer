/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net.download

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BlockReason
import us.dikarya.podplayer.core.net.FEED_HOST
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.Validators
import us.dikarya.podplayer.core.net.approvedFeedHost
import us.dikarya.podplayer.core.net.okBytes
import us.dikarya.podplayer.core.net.redirect
import us.dikarya.podplayer.core.net.startServer
import us.dikarya.podplayer.core.net.testBytes
import us.dikarya.podplayer.core.net.testGate
import us.dikarya.podplayer.core.net.urlOn

/**
 * SPEC §9 and the LESSONS "Downloads" row: resume with Range + If-Range, cope with servers that ignore
 * Range, time out on stalls, report progress, cancel cleanly, and never trust the declared size.
 */
class RangeDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var file: File
    private val gate = testGate()
    private val content = testBytes(64 * 1024)
    private val etag = "\"abc123\""

    @Before
    fun setUp() {
        server = startServer()
        file = File(temp.root, "episode.part")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun downloader(
        stall: StallPolicy = StallPolicy(),
        progressInterval: Duration = Duration.ZERO,
    ) = RangeDownloader(gate, stall, progressInterval)

    private fun request(validators: Validators? = null, maxBytes: Long = Long.MAX_VALUE, policy: AddressPolicy = approvedFeedHost) =
        DownloadRequest(server.urlOn(FEED_HOST, "/episode.mp3"), policy, validators, maxBytes)

    private fun download(
        request: DownloadRequest = request(),
        sink: DownloadSink = FileDownloadSink(file),
        rangeDownloader: RangeDownloader = downloader(),
        onProgress: DownloadProgressListener = DownloadProgressListener { _, _ -> },
    ): DownloadResult = runBlocking { rangeDownloader.download(request, sink, onProgress) }

    private fun full(bytes: ByteArray = content, configure: MockResponse.Builder.() -> Unit = {}) =
        okBytes(bytes) { addHeader("ETag", etag); addHeader("Content-Type", "audio/mpeg"); configure() }

    private fun partial(from: Int, bytes: ByteArray = content, tag: String = etag) =
        MockResponse.Builder().code(206)
            .addHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
            .addHeader("ETag", tag)
            .body(Buffer().write(bytes, from, bytes.size - from))
            .build()

    private fun writePartial(length: Int) = file.writeBytes(content.copyOf(length))

    @Test
    fun freshDownloadCompletes() {
        server.enqueue(full())
        val result = download() as DownloadResult.Complete
        assertEquals(content.size.toLong(), result.bytes)
        assertArrayEquals(content, file.readBytes())
        assertEquals(etag, result.validators.etag)
        assertEquals("audio/mpeg", result.contentType)
        assertEquals(0, result.resumedFrom)
        assertNull(result.restart)
        val sent = server.takeRequest()
        assertNull(sent.headers["Range"])
        assertEquals("downloads are never transparently gunzipped", "identity", sent.headers["Accept-Encoding"])
    }

    @Test
    fun resumesWithRangeAndIfRange() {
        writePartial(20_000)
        server.enqueue(partial(20_000))
        val result = download(request(Validators.of(etag, null))) as DownloadResult.Complete
        assertEquals(20_000, result.resumedFrom)
        assertNull(result.restart)
        assertArrayEquals(content, file.readBytes())
        val sent = server.takeRequest()
        assertEquals("bytes=20000-", sent.headers["Range"])
        assertEquals(etag, sent.headers["If-Range"])
    }

    @Test
    fun resumesWithLastModifiedWhenTheEtagIsWeak() {
        writePartial(1000)
        server.enqueue(partial(1000))
        val date = "Sun, 08 Nov 2026 10:00:00 GMT"
        download(request(Validators.of("W/\"weak\"", date)))
        assertEquals(date, server.takeRequest().headers["If-Range"])
    }

    @Test
    fun serverIgnoringRangeRestartsFromZero() {
        writePartial(20_000)
        server.enqueue(full())
        val result = download(request(Validators.of(etag, null))) as DownloadResult.Complete
        assertEquals(RestartReason.RangeIgnored, result.restart)
        assertEquals(0, result.resumedFrom)
        assertArrayEquals("the old bytes were discarded, not duplicated", content, file.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun changedFileRestartsFromZero() {
        writePartial(20_000)
        val newContent = testBytes(70_000).reversedArray()
        server.enqueue(okBytes(newContent) { addHeader("ETag", "\"different\"") })
        val result = download(request(Validators.of(etag, null))) as DownloadResult.Complete
        assertEquals(RestartReason.FileChanged, result.restart)
        assertArrayEquals(newContent, file.readBytes())
        assertEquals("\"different\"", result.validators.etag)
    }

    @Test
    fun partialFileWithoutValidatorRestartsWithoutRange() {
        writePartial(20_000)
        server.enqueue(full())
        val result = download(request(validators = null)) as DownloadResult.Complete
        assertEquals(RestartReason.NoValidator, result.restart)
        assertArrayEquals(content, file.readBytes())
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun wrongContentRangeStartRestartsCleanly() {
        writePartial(20_000)
        server.enqueue(partial(10_000))
        server.enqueue(full())
        val result = download(request(Validators.of(etag, null))) as DownloadResult.Complete
        assertEquals(RestartReason.BadContentRange, result.restart)
        assertArrayEquals(content, file.readBytes())
        server.takeRequest()
        assertNull("the retry asks for the whole file", server.takeRequest().headers["Range"])
    }

    @Test
    fun alreadyCompleteFileIsRecognisedFrom416() {
        file.writeBytes(content)
        server.enqueue(MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */${content.size}").build())
        val result = download(request(Validators.of(etag, null))) as DownloadResult.Complete
        assertEquals(content.size.toLong(), result.bytes)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun unexpected416RestartsFromZero() {
        writePartial(20_000)
        server.enqueue(MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */999").build())
        server.enqueue(full())
        val result = download(request(Validators.of(etag, null))) as DownloadResult.Complete
        assertEquals(RestartReason.RangeNotSatisfiable, result.restart)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun droppedConnectionKeepsThePartThenResumes() {
        server.enqueue(full { onResponseBody(SocketEffect.CloseSocket()) })
        val failed = download() as DownloadResult.Failed
        assertTrue("${failed.error}", failed.error is NetError.Network)
        assertTrue(failed.bytesOnDisk in 1 until content.size.toLong())
        assertEquals(failed.bytesOnDisk, file.length())
        assertEquals("the validator is kept for the resume", etag, failed.validators.etag)

        server.enqueue(partial(failed.bytesOnDisk.toInt()))
        val resumed = download(request(failed.validators)) as DownloadResult.Complete
        assertEquals(failed.bytesOnDisk, resumed.resumedFrom)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun chunkedResponseWithoutLengthCompletes() {
        // No Content-Length at all: accept when the transfer ends cleanly (SPEC §9).
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(content), 4096).build())
        val result = download() as DownloadResult.Complete
        assertEquals(content.size.toLong(), result.bytes)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun declaredSizeOverTheLimitIsRefusedBeforeWriting() {
        server.enqueue(full())
        val failed = download(request(maxBytes = 1000)) as DownloadResult.Failed
        assertEquals(NetError.TooLarge(1000), failed.error)
        assertEquals(0, file.length())
    }

    @Test
    fun undeclaredSizeOverTheLimitIsStoppedWhileReading() {
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(content), 4096).build())
        val failed = download(request(maxBytes = 10_000)) as DownloadResult.Failed
        assertEquals(NetError.TooLarge(10_000), failed.error)
        assertTrue(file.length() <= 10_000 + 64 * 1024)
    }

    @Test
    fun slowDripTriggersTheStallTimeout() {
        // 10 bytes every 100 ms: the socket never goes quiet for the read timeout, but it is a stall.
        server.enqueue(full { throttleBody(10, 100, TimeUnit.MILLISECONDS) })
        val failed = download(rangeDownloader = downloader(StallPolicy(window = 400.milliseconds, minBytes = 1024))) as DownloadResult.Failed
        assertEquals(NetError.Stalled, failed.error)
        assertEquals("what arrived is kept for a resume", failed.bytesOnDisk, file.length())
    }

    @Test
    fun slowButSteadyServerCompletes() {
        server.enqueue(full { throttleBody(8 * 1024, 50, TimeUnit.MILLISECONDS) })
        val result = download(rangeDownloader = downloader(StallPolicy(window = 400.milliseconds, minBytes = 1024)))
        assertTrue("$result", result is DownloadResult.Complete)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun silentServerTriggersTheStallTimeout() {
        server.enqueue(full { headersDelay(5, TimeUnit.SECONDS) })
        val failed = download(rangeDownloader = downloader(StallPolicy(window = 300.milliseconds, minBytes = 1))) as DownloadResult.Failed
        assertEquals(NetError.Stalled, failed.error)
        assertEquals(0, failed.bytesOnDisk)
    }

    @Test
    fun progressIsMonotonicAndEndsAtTheTotal() {
        server.enqueue(full())
        val reports = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        download(onProgress = { done, total -> reports += done to total })
        assertTrue(reports.isNotEmpty())
        assertEquals(reports.map { it.first }.sorted(), reports.map { it.first })
        assertEquals(content.size.toLong() to content.size.toLong(), reports.last())
    }

    @Test
    fun progressCountsTheResumedPart() {
        writePartial(20_000)
        server.enqueue(partial(20_000))
        val reports = Collections.synchronizedList(mutableListOf<Long>())
        download(request(Validators.of(etag, null)), onProgress = { done, _ -> reports += done })
        assertTrue(reports.first() >= 20_000)
        assertEquals(content.size.toLong(), reports.last())
    }

    @Test
    fun progressIsThrottled() {
        server.enqueue(full { throttleBody(1024, 5, TimeUnit.MILLISECONDS) })
        val reports = Collections.synchronizedList(mutableListOf<Long>())
        download(rangeDownloader = downloader(progressInterval = 10.seconds), onProgress = { done, _ -> reports += done })
        assertEquals("only the final report fits in a 10 s interval", listOf(content.size.toLong()), reports.toList())
    }

    @Test
    fun cancellationStopsWritingAndKeepsThePart() = runBlocking {
        server.enqueue(full { throttleBody(1024, 20, TimeUnit.MILLISECONDS) })
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            downloader().download(request(), FileDownloadSink(file)) { done, _ -> if (done >= 4096) started.complete(Unit) }
        }
        withTimeout(10.seconds) { started.await() }
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (expected: CancellationException) {
            // The coroutine was cancelled, as asked.
        }
        val lengthAfterCancel = file.length()
        Thread.sleep(300)
        assertEquals("nothing writes to the file after cancel returns", lengthAfterCancel, file.length())
        assertTrue(lengthAfterCancel in 4096 until content.size.toLong())

        server.enqueue(partial(lengthAfterCancel.toInt()))
        val resumed = downloader().download(request(Validators.of(etag, null)), FileDownloadSink(file)) { _, _ -> }
        assertTrue("$resumed", resumed is DownloadResult.Complete)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun diskFullFailsCleanly() {
        server.enqueue(full())
        val real = FileDownloadSink(file)
        val sink = object : DownloadSink by real {
            private var written = 0L
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (written + count > 10_000) throw IOException("ENOSPC (No space left on device)")
                written += count
                real.write(bytes, offset, count)
            }
        }
        val failed = download(sink = sink) as DownloadResult.Failed
        assertTrue("${failed.error}", failed.error is NetError.Storage)
        assertTrue("the partial file is left for the caller to delete or resume", failed.bytesOnDisk <= 10_000)
    }

    @Test
    fun redirectToAPrivateAddressWritesNothing() {
        server.enqueue(redirect(302, "http://10.0.0.1/episode.mp3"))
        val failed = download() as DownloadResult.Failed
        assertEquals(NetError.BlockedAddress("10.0.0.1", BlockReason.Private), failed.error)
        assertEquals(0, file.length())
    }

    @Test
    fun trackerRedirectChainsAreFollowed() {
        // Podcast enclosures commonly pass through two or three measurement redirects.
        server.enqueue(redirect(302, "/tracker2/episode.mp3"))
        server.enqueue(redirect(302, "/tracker3/episode.mp3"))
        server.enqueue(redirect(307, "/cdn/episode.mp3"))
        server.enqueue(full())
        val result = download() as DownloadResult.Complete
        assertEquals("/cdn/episode.mp3", result.redirects.finalUrl.encodedPath)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun httpErrorIsTyped() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(NetError.HttpStatus(404), (download() as DownloadResult.Failed).error)
    }

    @Test
    fun unapprovedHostIsRefused() {
        val failed = download(request(policy = AddressPolicy.Default)) as DownloadResult.Failed
        assertTrue(failed.error is NetError.BlockedAddress)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun contentRangeParsing() {
        assertEquals(ContentRange(100, 199, 1000), ContentRange.parse("bytes 100-199/1000"))
        assertEquals(ContentRange(0, 0, null), ContentRange.parse("bytes 0-0/*"))
        assertEquals(ContentRange(null, null, 1000), ContentRange.parse("bytes */1000"))
        for (bad in listOf(null, "", "bytes", "items 0-1/2", "bytes 5-1/10", "bytes 0-10/5", "bytes -1-5/10",
            "bytes 0-99999999999999999999/1", "bytes 1-2/3/4")) {
            assertNull(bad, ContentRange.parse(bad))
        }
    }
}
