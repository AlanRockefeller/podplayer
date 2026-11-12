/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.imagesandbox.ArtworkBitmaps
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate

/** The app as the service sees it: something that provides the playback dependencies. */
class TestPlaybackApp : Application(), PlaybackHost {
    override fun playbackDependencies(): PlaybackDependencies = deps

    companion object {
        lateinit var deps: PlaybackDependencies
    }
}

@RunWith(AndroidJUnit4::class)
@Config(application = TestPlaybackApp::class)
class PlaybackServiceTest {
    private val store = FakePlaybackStore().apply {
        add(1)
        add(2)
        positions[1] = 61_000
        current = 1
        queue.value = listOf(1, 2)
    }
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val noArtwork = object : ArtworkBitmaps {
        override fun isArtworkUri(uri: Uri) = false

        override suspend fun loadBitmap(uri: Uri): Bitmap? = null
    }

    init {
        val gate = NetGate(NetConfig(appVersion = "test"))
        TestPlaybackApp.deps = PlaybackDependencies(
            store = store,
            callFactoryFor = { gate.callFactory() },
            artwork = noArtwork,
            playGate = PlayGate { null },
            processScope = processScope,
            sessionActivity = null,
        )
    }

    private fun anyController() = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.android.systemui", 0, 10_100,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        false, Bundle.EMPTY, true,
    )

    @Test
    fun theServiceRestoresTheLoadedEpisodePausedWithSkipButtons() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        idle()
        val session = service.onGetSession(anyController())!!
        val player = session.player
        assertTrue(player is PodPlayer)
        assertEquals("1", player.currentMediaItem?.mediaId)
        assertEquals(61_000L, player.currentPosition)
        assertTrue("never starts by itself", !player.playWhenReady)
        assertEquals(listOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD), session.mediaButtonPreferences.map { it.playerCommand })
        controller.destroy()
        idle()
        assertEquals("the position is saved when the service ends", 61_000L, store.positions[1])
    }

    @Test
    fun theSessionUsesOurArtworkLoaderWhichRefusesRemoteUrisAndRawBytes() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        idle()
        val loader = controller.get().onGetSession(anyController())!!.bitmapLoader
        for (future in listOf(loader.loadBitmap(Uri.parse("https://evil.example/a.png")), loader.decodeBitmap(byteArrayOf(1, 2, 3)))) {
            idle()
            try {
                future.get()
                fail("Media3's own loader would have fetched or decoded this")
            } catch (expected: ExecutionException) {
                // Refused by ArtworkBitmapLoader.
            }
        }
        controller.destroy()
    }
}
