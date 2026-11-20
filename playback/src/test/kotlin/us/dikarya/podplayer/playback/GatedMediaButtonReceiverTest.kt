/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.imagesandbox.ArtworkBitmaps
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate

/**
 * Review PR #5: a Play media button while the service is stopped. Media3's receiver would start the service in
 * the foreground; an automatic Play the gate ignores must not start it, or Android stops the app when nothing
 * plays and the service never shows its notification.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestPlaybackApp::class)
class GatedMediaButtonReceiverTest {
    private val store = FakePlaybackStore()
    private var mediaButtonReason: String? = null
    private val app: Application = appContext() as Application

    init {
        val net = NetGate(NetConfig(appVersion = "test"))
        TestPlaybackApp.deps = PlaybackDependencies(
            store = store,
            callFactoryFor = { net.callFactory() },
            artwork = object : ArtworkBitmaps {
                override fun isArtworkUri(uri: Uri) = false

                override suspend fun loadBitmap(uri: Uri): Bitmap? = null
            },
            playGate = object : PlayGate {
                override fun ignoreReason(controller: MediaSession.ControllerInfo?): String? = null

                override fun ignoreMediaButtonReason(): String? = mediaButtonReason
            },
            processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            sessionActivity = null,
        )
    }

    private fun press(keyCode: Int = KeyEvent.KEYCODE_MEDIA_PLAY) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        GatedMediaButtonReceiver().onReceive(app, intent)
    }

    @Test
    fun anAutomaticPlayTheGateIgnoresNeverStartsTheService() {
        mediaButtonReason = "automatic Play 2 s after Sony CAR AUDIO connected"
        press()
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(store.logLines.any { "Ignored Play from a media button" in it })
    }

    @Test
    fun aPlayTheGateAllowsStartsTheServiceToResume() {
        press()
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(PlaybackService::class.java.name, started!!.component?.className)
        mediaButtonReason = null
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertNotNull(shadowOf(app).nextStartedService)
    }
}
