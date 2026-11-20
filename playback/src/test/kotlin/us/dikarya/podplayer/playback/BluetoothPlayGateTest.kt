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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner's car (an old Sony stereo) sends Play every time it starts. The owner ticks it in Settings, and its
 * first Play after connecting is ignored; the next one plays. Every other device is left alone (owner,
 * 2026-11-15: per device, opt-in, so nobody's headphones seem broken).
 *
 * Seen on the owner's phone (2026-11-13): the car connected at 15:15:38 and its Play cold-started the app 22 s
 * later; the first gate (5 s / 10 s windows) let it through.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothPlayGateTest {
    private var now = 1_000_000L
    private val outputs = mutableMapOf<Int, String>()
    private val chosen = mutableSetOf(CAR)
    private val seen = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    private fun gate(startedAt: Long = now) = BluetoothPlayGate(
        clock = { now },
        processStartedAt = startedAt,
        bluetoothOutputs = { outputs.toMap() },
        ignoreAutoPlayFrom = { chosen.toSet() },
        onDeviceSeen = { seen += it },
        log = { logged += it },
    )

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

    private val carController = controller("com.android.bluetooth", 1002)
    private val ownUi = controller(appContext().packageName, Process.myUid(), ownUi = true)

    // Our own process, but no hint: the media notification, or Media3's media-button caller after a cold start.
    private val mediaButton = controller(appContext().packageName, Process.myUid())

    private fun connect(gate: BluetoothPlayGate, id: Int, name: String) {
        outputs[id] = name
        gate.onBluetoothOutputsAdded(setOf(id))
    }

    @Test
    fun theCarsFirstPlayAfterConnectingIsIgnoredAndTheNextPlays() {
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 77, CAR)
        now += 3_000
        assertNotNull(gate.ignoreReason(carController))
        assertNull("the owner's own press plays", gate.ignoreReason(carController))
    }

    @Test
    fun aDeviceTheOwnerDidNotTickIsNeverIgnored() {
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 78, "Pixel Buds")
        now += 1_000
        assertNull(gate.ignoreReason(carController))
    }

    @Test
    fun withNothingTickedNothingIsIgnored() {
        chosen.clear()
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 77, CAR)
        assertNull(gate.ignoreReason(carController))
    }

    @Test
    fun beforeTheTickedListIsReadEveryFreshDeviceCountsAsTicked() {
        // A cold start by the car: its Play can arrive before Settings has been read. Unknown is not "none
        // ticked" (CLAUDE.md: never auto-start on Bluetooth connect), so the first outside Play is ignored.
        var loaded: Set<String>? = null
        outputs[78] = "Pixel Buds"
        val gate = BluetoothPlayGate(
            clock = { now },
            processStartedAt = now,
            bluetoothOutputs = { outputs.toMap() },
            ignoreAutoPlayFrom = { loaded },
            log = { logged += it },
        )
        now += 1_000
        assertNotNull(gate.ignoreReason(mediaButton))
        assertNull("the next press plays", gate.ignoreReason(mediaButton))
        // Once read, the owner's list decides again.
        loaded = setOf(CAR)
        connect(gate, 79, "Pixel Buds")
        assertNull(gate.ignoreReason(carController))
        // A list read as empty (nothing ticked) is not "unread": nothing is ignored.
        loaded = emptySet()
        connect(gate, 80, CAR)
        assertNull(gate.ignoreReason(carController))
    }

    @Test
    fun beforeTheTickedListIsReadAPlayWithNoFreshDevicePlays() {
        val gate = BluetoothPlayGate(
            clock = { now },
            processStartedAt = now - 3_600_000,
            bluetoothOutputs = { outputs.toMap() },
            ignoreAutoPlayFrom = { null },
        )
        assertNull(gate.ignoreReason(mediaButton))
    }

    @Test
    fun aCarThatSendsPlayHalfAMinuteAfterConnectingIsStillIgnored() {
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 77, CAR)
        now += 30_000
        assertNotNull(gate.ignoreReason(carController))
    }

    @Test
    fun aColdStartByTheCarIsIgnoredEvenWhenStartupIsSlow() {
        // The car connected before the process existed; its Play started us; the library took 20 s to open.
        outputs[77] = CAR
        val gate = gate(startedAt = now)
        now += 20_000
        assertNotNull(gate.ignoreReason(mediaButton))
        assertNull(gate.ignoreReason(mediaButton))
    }

    @Test
    fun aConnectionTheAppHasNotHeardAboutYetStillCounts() {
        // The app was frozen in the background, so the connection callback hasn't arrived when the Play does.
        val gate = gate(startedAt = now - 3_600_000)
        now += 5_000
        outputs[79] = CAR
        assertNotNull(gate.ignoreReason(carController))
    }

    @Test
    fun aCarConnectedLongAgoDoesNotBlockTheFirstSteeringWheelPress() {
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 77, CAR)
        now += BluetoothPlayGate.ARM_WINDOW_MS + 1
        assertNull(gate.ignoreReason(carController))
    }

    @Test
    fun ourOwnScreensAlwaysPlayAndCancelThePendingIgnore() {
        outputs[77] = CAR
        val gate = gate()
        assertNull(gate.ignoreReason(ownUi))
        assertNull(gate.ignoreReason(carController))
        assertNull("the engine itself (no controller) is never gated", gate.ignoreReason(null))
    }

    @Test
    fun openingTheAppCancelsThePendingIgnore() {
        outputs[77] = CAR
        val gate = gate()
        gate.ownerIsHere()
        assertNull(gate.ignoreReason(carController))
        // A connection after the owner opened the app is new again.
        connect(gate, 80, CAR)
        assertNotNull(gate.ignoreReason(carController))
    }

    @Test
    fun anotherAppClaimingTheHintIsNotOurUi() {
        outputs[77] = CAR
        val gate = gate()
        assertNotNull(gate.ignoreReason(controller(appContext().packageName, 10_999, ownUi = true)))
    }

    @Test
    fun aDisconnectedCarIsForgotten() {
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 77, CAR)
        outputs.remove(77)
        now += 1_000
        assertNull(gate.ignoreReason(carController))
    }

    @Test
    fun everyDeviceIsRememberedByNameForTheSettingsList() {
        outputs[76] = "Kitchen speaker"
        val gate = gate()
        connect(gate, 77, CAR)
        outputs[81] = "Pixel Buds"
        gate.ignoreReason(carController)
        assertEquals(listOf("Kitchen speaker", CAR, "Pixel Buds"), seen)
    }

    @Test
    fun everyOutsidePlayIsLoggedWithTheDeviceAndTheDelay() {
        val gate = gate(startedAt = now - 3_600_000)
        connect(gate, 77, CAR)
        now += 7_000
        gate.ignoreReason(carController)
        gate.ignoreReason(carController)
        assertTrue(logged.toString(), logged.any { "ignored" in it && CAR in it && "7 s" in it })
        assertTrue(logged.toString(), logged.any { "allowed" in it && CAR in it })
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
        outputs[77] = CAR
        val gate = gate()
        h.engine.playGate = gate
        h.engine.currentController = { carController }

        h.engine.sessionPlayer.play()
        idle()
        assertFalse(h.player.playWhenReady)
        assertTrue(h.store.logLines.any { "Ignored Play" in it && "com.android.bluetooth" in it })

        h.engine.sessionPlayer.playWhenReady = true
        idle()
        assertTrue("the second press plays", h.player.playWhenReady)
    }

    private companion object {
        const val CAR = "Sony CAR AUDIO"
    }
}
