/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A media button's Play before the service runs is decided like any outside Play, once. */
@RunWith(AndroidJUnit4::class)
class BluetoothPlayGateMediaButtonTest {
    private var now = 1_000_000L
    private val outputs = mutableMapOf<Int, String>()
    private val logged = mutableListOf<String>()

    private var ticked = setOf("Sony CAR AUDIO")

    private fun gate(startedAt: Long) = BluetoothPlayGate(
        clock = { now },
        processStartedAt = startedAt,
        bluetoothOutputs = { outputs.toMap() },
        ignoreAutoPlayFrom = { ticked },
        log = { logged += it },
    )

    private val carController = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.android.bluetooth", 0, 1002,
        androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
        androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        false, android.os.Bundle.EMPTY, true,
    )

    @Test
    fun aMediaButtonAllowedBeforeTheTickedListIsReadLeavesTheServiceToDecide() {
        outputs[77] = "Sony CAR AUDIO"
        ticked = emptySet() // not read yet this early in a cold start
        val gate = gate(startedAt = now)
        now += 1_000
        assertNull("the service starts", gate.ignoreMediaButtonReason())
        // By the time the session hands the same Play to the player, the list is read: still the car's automatic Play.
        ticked = setOf("Sony CAR AUDIO")
        now += 1_000
        assertNotNull(gate.ignoreReason(carController))
        assertNull("the next press plays", gate.ignoreReason(carController))
    }

    @Test
    fun aTickedCarsPlayThatStartedTheProcessIsIgnoredOnceThenTheNextPlays() {
        outputs[77] = "Sony CAR AUDIO"
        // The car connected and pressed Play; that broadcast started the process.
        val gate = gate(startedAt = now)
        now += 2_000
        assertNotNull(gate.ignoreMediaButtonReason())
        assertTrue(logged.single().startsWith("Play from a media button: ignored"))
        assertNull("the owner's own press plays", gate.ignoreMediaButtonReason())
    }

    @Test
    fun anUntickedDeviceOrNoDeviceStartsPlaybackAsBefore() {
        val gate = gate(startedAt = now - 3_600_000)
        assertNull(gate.ignoreMediaButtonReason())
        outputs[78] = "Pixel Buds"
        gate.onBluetoothOutputsAdded(setOf(78))
        assertNull(gate.ignoreMediaButtonReason())
    }
}
