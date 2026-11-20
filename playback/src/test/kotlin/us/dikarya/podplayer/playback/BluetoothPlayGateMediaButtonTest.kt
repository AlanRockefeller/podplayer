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

    private fun gate(startedAt: Long) = BluetoothPlayGate(
        clock = { now },
        processStartedAt = startedAt,
        bluetoothOutputs = { outputs.toMap() },
        ignoreAutoPlayFrom = { setOf("Sony CAR AUDIO") },
        log = { logged += it },
    )

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
