/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.HttpUrl
import okio.Buffer
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.backup.FileBackupFolder
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.jobs.download.AudioProbe
import us.dikarya.podplayer.core.jobs.download.DownloadRunner
import us.dikarya.podplayer.core.jobs.download.KeyedLocks
import us.dikarya.podplayer.core.jobs.refresh.FeedRefresher
import us.dikarya.podplayer.core.jobs.refresh.androidFeedParser
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.core.net.download.DownloadSink
import us.dikarya.podplayer.core.net.download.FileDownloadSink
import us.dikarya.podplayer.core.net.download.RangeDownloader
import us.dikarya.podplayer.core.net.download.StallPolicy

internal fun appContext(): Context = ApplicationProvider.getApplicationContext()

internal class TestClock(var now: Long = 1_793_000_000_000L) : () -> Long {
    override fun invoke(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** A host the owner typed; it resolves to the loopback test server. */
internal const val FEED_HOST = "feeds.example.test"

/** A second host for the same server; nobody approved it, so the address filter refuses it. */
internal const val OTHER_HOST = "other.example.test"

/** Resolves every test host to the loopback server. Never touches real DNS. */
private class LoopbackResolver : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.endsWith(".example.test")) listOf(LOOPBACK) else throw UnknownHostException(hostname)
}

/** Answers by path, records every request, and serves files with real Range and If-Range handling. */
internal class Routes : Dispatcher() {
    private val handlers = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    operator fun set(path: String, handler: (RecordedRequest) -> MockResponse) {
        handlers[path] = handler
    }

    fun count(path: String): Int = counts[path]?.get() ?: 0

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == path }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        return handlers[path]?.invoke(request) ?: MockResponse.Builder().code(404).build()
    }

    /** Serves [bytes] at [path] like a well-behaved static file server. */
    fun file(path: String, bytes: ByteArray, etag: String = "\"v1\"", type: String = "audio/mpeg", rangeSupport: Boolean = true) {
        this[path] = { request -> fileResponse(request, bytes, etag, type, rangeSupport) }
    }

    companion object {
        fun fileResponse(request: RecordedRequest, bytes: ByteArray, etag: String, type: String, rangeSupport: Boolean): MockResponse {
            val range = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            val ifRange = request.headers["If-Range"]
            if (rangeSupport && range != null && (ifRange == null || ifRange == etag) && range < bytes.size) {
                return MockResponse.Builder().code(206)
                    .addHeader("ETag", etag)
                    .addHeader("Content-Type", type)
                    .addHeader("Content-Range", "bytes $range-${bytes.size - 1}/${bytes.size}")
                    .body(Buffer().write(bytes, range, bytes.size - range))
                    .build()
            }
            return MockResponse.Builder()
                .addHeader("ETag", etag)
                .addHeader("Content-Type", type)
                .body(Buffer().write(bytes))
                .build()
        }
    }
}

internal data class Item(
    val guid: String,
    val title: String,
    val url: String,
    val length: Long? = 1234,
    val type: String = "audio/mpeg",
    val pubDate: String? = "Sat, 07 Nov 2026 10:00:00 GMT",
)

internal fun feedXml(items: List<Item>, title: String = "Test Show"): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""")
    append("""<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>""")
    append("<title>").append(title).append("</title><link>https://show.example.test/</link>")
    for (item in items) {
        append("<item><guid>").append(item.guid).append("</guid><title>").append(item.title).append("</title>")
        item.pubDate?.let { append("<pubDate>").append(it).append("</pubDate>") }
        append("<enclosure url=\"").append(item.url.replace("&", "&amp;")).append("\" type=\"").append(item.type).append('"')
        item.length?.let { append(" length=\"").append(it).append('"') }
        append("/></item>")
    }
    append("</channel></rss>")
}

internal fun xmlResponse(xml: String, etag: String? = null): MockResponse = MockResponse.Builder()
    .addHeader("Content-Type", "application/rss+xml; charset=utf-8")
    .apply { etag?.let { addHeader("ETag", it) } }
    .body(xml)
    .build()

/** Synthetic audio, built in code so no media file is committed. */
internal object TestAudio {
    /** MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, stereo, no padding: 417 bytes and 1,152 samples per frame. */
    fun mp3(frames: Int): ByteArray {
        val frame = ByteArray(417).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xFB.toByte()
            it[2] = 0x90.toByte()
            it[3] = 0x00
        }
        return ByteArray(frames * frame.size).also { out -> repeat(frames) { frame.copyInto(out, it * frame.size) } }
    }

    fun mp3DurationMs(frames: Int): Long = frames * 1152L * 1000 / 44_100

    /** 16-bit mono PCM at 8 kHz. */
    fun wav(millis: Int): ByteArray {
        val dataBytes = 8_000 * 2 * millis / 1000
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        return buffer.array()
    }

    /**
     * FLV with an AAC sequence header and [packets] audio tags 23 ms apart, and no `onMetaData`, so the
     * container states no duration. With [declareVideo] the header also announces a video track that never
     * comes: a stream whose preparation can never finish.
     */
    fun flv(packets: Int, packetBytes: Int = 300, declareVideo: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u24(v: Int) {
            out.write(v ushr 16 and 0xFF)
            out.write(v ushr 8 and 0xFF)
            out.write(v and 0xFF)
        }
        fun u32(v: Int) {
            out.write(v ushr 24 and 0xFF)
            u24(v and 0xFFFFFF)
        }
        fun tag(timestampMs: Int, data: ByteArray) {
            out.write(8)
            u24(data.size)
            u24(timestampMs and 0xFFFFFF)
            out.write(timestampMs ushr 24 and 0xFF)
            u24(0)
            out.write(data)
            u32(11 + data.size)
        }
        out.write("FLV".toByteArray())
        out.write(1)
        out.write(if (declareVideo) 0x05 else 0x04)
        u32(9)
        u32(0)
        tag(0, byteArrayOf(0xAF.toByte(), 0x00, 0x12, 0x10))
        val payload = ByteArray(packetBytes).also {
            it[0] = 0xAF.toByte()
            it[1] = 0x01
        }
        for (i in 0 until packets) tag(i * 23, payload)
        return out.toByteArray()
    }

    /** The classic 1x1 PNG. Only its header matters here: it must be recognised as an image, never decoded. */
    val png: ByteArray = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
        0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
        0x42, 0x60, 0x82,
    ).map { it.toByte() }.toByteArray()
}

/**
 * One app's worth of jobs over a temporary folder: a real data layer (Room, settings), the real gated client
 * pointed at a local [MockWebServer] through a fake resolver, the refresher and the download runner.
 */
internal class TestEnv(
    root: File,
    stallPolicy: StallPolicy = StallPolicy(window = 3.seconds, minBytes = 1),
    sinkFactory: (File) -> DownloadSink = ::FileDownloadSink,
    probe: AudioProbe = AudioProbe(),
) : Closeable {
    val clock = TestClock()
    val routes = Routes()
    val server = MockWebServer().apply {
        dispatcher = routes
        start(LOOPBACK, 0)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val log = AppLog({ File(root, "logs") }, clock, echo = false)
    val files = DataFiles.under(File(root, "app"))
    val data = DataLayer(
        context = appContext(),
        scope = scope,
        appVersion = "9.9.9-test",
        log = log,
        clock = clock,
        filesProvider = { files },
        ownerFolder = { FileBackupFolder(File(it)) },
    ).also { it.start() }
    val gate = NetGate(NetConfig(appVersion = "9.9.9-test", connectTimeout = 2.seconds, readTimeout = 2.seconds), LoopbackResolver())
    val locks = KeyedLocks()
    val refresher = FeedRefresher(
        repositories = { data.awaitRepositories() },
        gate = gate,
        parser = androidFeedParser(),
        log = log,
        scope = scope,
        clock = clock,
    )
    val runner = DownloadRunner(
        repositories = { data.awaitRepositories() },
        paths = data.paths,
        storageCap = { data.settings.storageCapBytes.first() },
        engine = RangeDownloader(gate, stallPolicy),
        probe = probe,
        log = log,
        locks = locks,
        clock = clock,
        sinkFactory = sinkFactory,
    )

    fun repos(): Repositories = runBlocking {
        withTimeout(30_000) { data.awaitRepositories() } ?: error("the library didn't open: ${data.state.value}")
    }

    fun url(path: String, host: String = FEED_HOST): HttpUrl = server.url(path).newBuilder().host(host).build()

    /** Subscribes as if the owner typed the feed's URL, so its host may be the loopback test server. */
    fun subscribe(path: String = "/feed.xml", ownerTyped: Boolean = true, query: String? = "token=SECRETTOKEN"): Long = runBlocking {
        val url = url(path).newBuilder().query(query).build().toString()
        (repos().feeds.subscribe(url, ownerTyped) as SubscribeResult.Subscribed).feedId
    }

    override fun close() {
        runCatching { runBlocking { data.close() } }
        server.close()
        scope.cancel()
    }
}
