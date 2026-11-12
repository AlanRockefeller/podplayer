/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.os.Bundle
import android.os.Process
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CLAUDE.md "Never auto-start on Bluetooth connect", and the owner's car, which sends Play every time it connects.
 * A Play from anything but our own screens is ignored right after Bluetooth audio appears, or right after a cold
 * start while Bluetooth audio is connected (the car's Play is what started the process).
 */
@RunWith(AndroidJUnit4::class)
class BluetoothPlayGateTest {
    private var now = 1_000_000L
    private var bluetoothConnected = true
    private val processStart = 1_000_000L - 60_000

    private fun gate(started: Long = processStart) =
        BluetoothPlayGate(clock = { now }, processStartedAt = started, bluetoothOutputConnected = { bluetoothConnected })

    private fun controller(packageName: String, uid: Int, ownUi: Boolean = false): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            0,
            uid,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            false,
            Bundle().apply { if (ownUi) putBoolean(PlaybackClient.HINT_OWN_UI, true) },
            true,
        )

    private val car = controller("com.android.bluetooth", 1002)
    private val ownUi = controller(appContext().packageName, Process.myUid(), ownUi = true)

    // Our own process, but no hint: the media notification, or Media3's media-button caller after a cold start.
    private val notification = controller(appContext().packageName, Process.myUid())

    @Test
    fun aPlayJustAfterBluetoothAudioConnectsIsIgnored() {
        val gate = gate()
        gate.onBluetoothOutputAdded()
        now += 3_000
        assertNotNull(gate.ignoreReason(car))
        assertNotNull("a media-button caller in our own process is not our UI", gate.ignoreReason(notification))
        now += BluetoothPlayGate.WINDOW_MS
        assertNull("a press after the window plays", gate.ignoreReason(car))
    }

    @Test
    fun ourOwnScreensAlwaysPlay() {
        val gate = gate()
        gate.onBluetoothOutputAdded()
        assertNull(gate.ignoreReason(ownUi))
        assertNull("the engine itself (no controller) is never gated", gate.ignoreReason(null))
    }

    @Test
    fun anotherAppClaimingTheHintIsNotOurUi() {
        val gate = gate()
        gate.onBluetoothOutputAdded()
        assertNotNull(gate.ignoreReason(controller(appContext().packageName, 10_999, ownUi = true)))
    }

    @Test
    fun withoutBluetoothAudioNothingIsIgnored() {
        bluetoothConnected = false
        val gate = gate(started = now)
        gate.onBluetoothOutputAdded()
        assertNull(gate.ignoreReason(car))
    }

    @Test
    fun aColdStartWithBluetoothAudioIgnoresTheFirstPlay() {
        // The process was started seconds ago by the car's Play; the car connected before we could watch.
        val gate = gate(started = now - 2_000)
        assertNotNull(gate.ignoreReason(car))
        now += BluetoothPlayGate.COLD_START_WINDOW_MS
        assertNull(gate.ignoreReason(car))
    }

    @Test
    fun bluetoothThatWasAlreadyConnectedLongAgoDoesNotBlock() {
        assertNull(gate().ignoreReason(car))
    }

    // --- Through the engine: the same check guards every way a Play reaches the player ---

    private val h = Harness()

    @After
    fun tearDown() = h.release()

    @Test
    fun theEngineIgnoresTheCarsPlayButNotTheOwnersSecondPress() {
        h.store.add(1)
        h.store.current = 1
        h.start()
        val gate = gate()
        gate.onBluetoothOutputAdded()
        var caller: MediaSession.ControllerInfo? = car
        h.engine.playGate = gate
        h.engine.currentController = { caller }

        h.engine.sessionPlayer.play()
        idle()
        assertFalse(h.player.playWhenReady)
        h.engine.sessionPlayer.playWhenReady = true
        idle()
        assertFalse(h.player.playWhenReady)
        assertTrue(h.store.logLines.any { "Ignored Play" in it && "com.android.bluetooth" in it })

        now += BluetoothPlayGate.WINDOW_MS + 1
        h.engine.sessionPlayer.play()
        idle()
        assertTrue(h.player.playWhenReady)

        h.engine.sessionPlayer.pause()
        caller = ownUi
        now = 2_000_000
        gate.onBluetoothOutputAdded()
        h.engine.sessionPlayer.play()
        idle()
        assertTrue("the owner's tap in the app always plays", h.player.playWhenReady)
    }
}
