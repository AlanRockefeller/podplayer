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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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

    /** One second of 8 kHz mono 16-bit silence. */
    private fun wav(): ByteArray {
        val samples = 8_000
        val data = samples * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }
}
