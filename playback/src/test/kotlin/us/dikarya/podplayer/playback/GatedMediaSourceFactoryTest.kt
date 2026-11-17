/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate

/**
 * CLAUDE.md: all traffic, ExoPlayer included, goes through the gated client; feed-supplied URLs never reach a
 * private address unless it's the feed's owner-approved host. Real NetGate, real extractors, a local server.
 */
@RunWith(AndroidJUnit4::class)
class GatedMediaSourceFactoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val gate = NetGate(NetConfig(appVersion = "test"))
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        server.start()
        val clock = FakeClock(true)
        val sources = GatedMediaSourceFactory { host ->
            gate.callFactory(if (host == null) AddressPolicy.Default else AddressPolicy.approvingHosts(host))
        }
        player = PlayerFactory.create(appContext(), sources, clock, fakeRenderers(clock))
    }

    @After
    fun tearDown() {
        player.release()
        server.close()
    }

    private fun wavResponse() = MockResponse.Builder().addHeader("Content-Type", "audio/wav").body(Buffer().write(wav())).build()

    private fun episode(streamUrl: String, approvedHost: String? = null, file: File? = null) = PlayableEpisode(
        id = 1, feedId = 1, title = "One", showTitle = "Show", streamUrl = streamUrl,
        localFile = file, approvedHost = approvedHost, artworkUri = null, durationMs = null,
    )

    private fun play(item: MediaItem) {
        player.setMediaItem(item)
        player.prepare()
    }

    @Test
    fun aStreamOnTheOwnerApprovedPrivateHostPlaysThroughTheGate() {
        server.enqueue(wavResponse())
        val url = server.url("/a.wav")
        play(episode(url.toString(), approvedHost = url.host).toMediaItem(useFile = false))
        run(player).untilState(Player.STATE_READY)
        val request = server.takeRequest()
        assertEquals("the request came from the gated client", "Podplayer/test (Android)", request.headers["User-Agent"])
    }

    @Test
    fun aStreamOnAPrivateAddressWithoutApprovalIsRefusedBeforeAnyRequest() {
        server.enqueue(wavResponse())
        play(episode(server.url("/a.wav").toString()).toMediaItem(useFile = false))
        run(player).untilPlayerError()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aDownloadedFilePlaysWithoutTheNetwork() {
        val file = File(tmp.root, "1.wav").apply { writeBytes(wav()) }
        play(episode(server.url("/never.wav").toString(), file = file).toMediaItem(useFile = true))
        run(player).untilState(Player.STATE_READY)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aStreamUrlThatIsNotHttpIsNeverOpened() {
        val secret = File(tmp.root, "secret.wav").apply { writeBytes(wav()) }
        play(episode("file://${secret.path}").toMediaItem(useFile = false))
        val error = run(player).untilPlayerError()
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { "Refused to open" in (it.message ?: "") })
    }

    /**
     * The engine adds artwork fetched after an episode started by replacing its item's metadata (M1-S8). That only
     * leaves the audio alone when the source accepts the new item in place; otherwise the player would rebuild it
     * and start the episode over.
     */
    @Test
    fun newMetadataForAnItemUpdatesItsSourceInPlace() {
        val factory = GatedMediaSourceFactory { gate.callFactory() }
        val file = File(tmp.root, "1.wav").apply { writeBytes(wav()) }
        val items = listOf(
            // A host in capitals and an escaped space: the policy's canonical form differs from the feed's text.
            episode("https://Media.Example.COM/shows/a%20b.mp3?token=1").toMediaItem(useFile = false),
            episode("https://media.example.com/a.mp3", file = file).toMediaItem(useFile = true),
        )
        for (item in items) {
            val source = factory.createMediaSource(item)
            val withArt = item.buildUpon()
                .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(Uri.parse("content://us.dikarya.podplayer.artwork/a.jpg")).build())
                .build()
            assertTrue("$item", source.canUpdateMediaItem(withArt))
        }
    }

    @Test
    fun anItemWithoutOurTagIsNeverOpened() {
        server.enqueue(wavResponse())
        play(MediaItem.fromUri(server.url("/a.wav").toString()))
        run(player).untilPlayerError()
        assertEquals(0, server.requestCount)
    }

    /**
     * The owner's phone, 2026-11-17: an HTTP/2 stream ended 26 minutes into a 57-minute episode. OkHttp doesn't
     * check HTTP/2 bodies against their Content-Length, so Media3 took the cut for the end of the file and played
     * the rest of the duration as silence, the position still advancing. The first response here keeps its
     * Content-Length but ends early, as that one did; playback must ask for the rest and reach the real end.
     */
    @Test
    fun aStreamCutShortOfItsLengthResumesFromWhereItStopped() {
        val whole = wav(seconds = 2)
        val cut = 10_000
        server.dispatcher = rangesOf(whole)
        player.release()
        val clock = FakeClock(true)
        player = PlayerFactory.create(appContext(), cutShortSources(keep = cut, times = 1), clock, fakeRenderers(clock))
        val url = server.url("/a.wav")
        play(episode(url.toString(), approvedHost = url.host).toMediaItem(useFile = false))
        player.play()

        // The cut is reported as a non-fatal load error: that's the retry. A fatal one would still fail here.
        run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED)

        assertEquals("the cut first response, then one for the rest", 2, server.requestCount)
        assertEquals(null, server.takeRequest().headers["Range"])
        val resumedAt = server.takeRequest().headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
        assertTrue("resumed inside the part already received, at $resumedAt", resumedAt != null && resumedAt in 44L..cut)
    }

    /**
     * The owner asked for a brief notice when a stream reconnects. Two drops a few seconds apart give one notice
     * (no toast per attempt) and one log line, which never holds the URL.
     */
    @Test
    fun aReconnectingStreamShowsOneBriefNoticeAndLogsIt() {
        server.dispatcher = rangesOf(wav(seconds = 2))
        player.release()
        val clock = FakeClock(true)
        player = PlayerFactory.create(appContext(), cutShortSources(keep = 10_000, times = 2), clock, fakeRenderers(clock))
        val store = FakePlaybackStore()
        val url = server.url("/shows/a.wav?token=secret")
        store.episodes[1] = episode(url.toString(), approvedHost = url.host)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = PlaybackEngine(player, store, scope, clock)
        var notices = 0
        engine.onStreamReconnecting = { notices++ }
        try {
            engine.start()
            engine.playEpisode(1)
            player.play()
            run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED)

            assertEquals("cut twice, then the rest", 3, server.requestCount)
            assertEquals(1, notices)
            val lines = store.logLines.filter { "reconnecting" in it }
            assertEquals(listOf("Episode 1: the stream dropped; reconnecting (attempt 1)"), lines)
            assertTrue(store.logLines.none { "secret" in it || "a.wav" in it })
        } finally {
            engine.release()
            scope.cancel()
        }
    }

    /** The gate's client, with the first [times] responses cut to [keep] bytes but keeping their full length. */
    private fun cutShortSources(keep: Int, times: Int): GatedMediaSourceFactory {
        var cutsLeft = times
        return GatedMediaSourceFactory { host ->
            val gated = gate.callFactory(if (host == null) AddressPolicy.Default else AddressPolicy.approvingHosts(host))
            Call.Factory { request ->
                val call = gated.newCall(request)
                if (cutsLeft > 0) {
                    cutsLeft--
                    CutShortCall(call, keep)
                } else {
                    call
                }
            }
        }
    }

    /** Serves [body] whole, or the part a `Range: bytes=n-` header asks for. */
    private fun rangesOf(body: ByteArray) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val from = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                ?: return MockResponse.Builder().addHeader("Content-Type", "audio/wav").body(Buffer().write(body)).build()
            return MockResponse.Builder()
                .code(206)
                .addHeader("Content-Type", "audio/wav")
                .addHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                .body(Buffer().write(body, from, body.size - from))
                .build()
        }
    }

    /** Delivers only the first [keep] bytes of the response but keeps its full length, like a cut HTTP/2 stream. */
    private class CutShortCall(private val real: Call, private val keep: Int) : Call by real {
        override fun enqueue(responseCallback: Callback) = real.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(this@CutShortCall, e)

                override fun onResponse(call: Call, response: Response) {
                    val type = response.body.contentType()
                    val whole = response.body.bytes()
                    val short = Buffer().write(whole, 0, keep).asResponseBody(type, whole.size.toLong())
                    responseCallback.onResponse(this@CutShortCall, response.newBuilder().body(short).build())
                }
            },
        )

        override fun clone(): Call = CutShortCall(real.clone(), keep)
    }

    /** [seconds] of 8 kHz mono 16-bit silence. */
    private fun wav(seconds: Int = 1): ByteArray {
        val samples = 8_000 * seconds
        val data = samples * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }
}
