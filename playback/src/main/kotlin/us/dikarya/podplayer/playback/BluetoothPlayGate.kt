/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.session.MediaSession
import us.dikarya.podplayer.core.data.settings.AppSettings

/** Decides whether a Play request is obeyed. Null: play. Otherwise the reason it is ignored. */
fun interface PlayGate {
    fun ignoreReason(controller: MediaSession.ControllerInfo?): String?
}

/**
 * CLAUDE.md: never auto-start on Bluetooth connect. Many cars (the owner's old Sony stereo included) send Play as
 * soon as they connect, and Android delivers it like a real button press. Every Play reaches the player through the
 * session, which tells us who sent it ([MediaSession.getControllerForCurrentRequest]), so one check covers the lock
 * screen, media buttons, Bluetooth controllers and playback resumption after a cold start.
 *
 * Per device and opt-in (owner, 2026-11-15): the owner ticks the car by name in Settings ([ignoreAutoPlayFrom]).
 * **The first Play from outside our own screens within [ARM_WINDOW_MS] of a ticked device connecting is ignored**;
 * every later Play plays, and unticked devices are never touched, so nobody's headphones seem broken.
 * - A device that connects while the app runs is "armed" from the moment we hear of it.
 * - Devices already connected when the process starts are armed from the process start: a car's Play is often
 *   what started it, and Android gives no connection time without the Bluetooth permission.
 * - At each Play the connected devices are read directly, so a connection we haven't been told about yet (the app
 *   was frozen in the background) still counts.
 * Our own screens always play, and the owner opening the app ([ownerIsHere]) cancels what is armed. Every outside
 * Play is logged with the device and the delay, and every device name is passed to [onDeviceSeen] for the list.
 * Names come from nearby hardware: they are only compared and shown, never interpreted.
 */
class BluetoothPlayGate(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    processStartedAt: Long = Process.getStartElapsedRealtime(),
    /** Connected Bluetooth audio outputs: device id → name. */
    private val bluetoothOutputs: () -> Map<Int, String>,
    /** The names the owner ticked; read at each Play. */
    private val ignoreAutoPlayFrom: () -> Set<String>,
    private val onDeviceSeen: (String) -> Unit = {},
    private val ownUid: Int = Process.myUid(),
    private val log: (String) -> Unit = {},
) : PlayGate {
    private val lock = Any()

    /** Device ids we've seen, so a reconnection (a new id) counts as new. */
    private val seen = mutableSetOf<Int>()

    /** Device id → when it became armed. */
    private val armed = mutableMapOf<Int, Long>()

    init {
        for ((id, name) in bluetoothOutputs()) see(id, name, processStartedAt)
    }

    private fun see(id: Int, name: String, at: Long) {
        if (!seen.add(id)) return
        armed[id] = at
        onDeviceSeen(name)
    }

    fun onBluetoothOutputsAdded(ids: Set<Int>) = synchronized(lock) {
        val now = clock()
        val outputs = bluetoothOutputs()
        for (id in ids) see(id, outputs[id] ?: continue, now)
    }

    /** The owner opened the app: whatever is connected now was connected on purpose. */
    fun ownerIsHere() = synchronized(lock) {
        for ((id, name) in bluetoothOutputs()) see(id, name, clock())
        armed.clear()
    }

    override fun ignoreReason(controller: MediaSession.ControllerInfo?): String? = synchronized(lock) {
        if (controller == null) return null
        val now = clock()
        val present = bluetoothOutputs()
        for ((id, name) in present) see(id, name, now)
        armed.keys.retainAll(present.keys)
        if (isOwnUi(controller)) {
            armed.clear()
            return null
        }
        val ticked = ignoreAutoPlayFrom()
        val fresh = armed.filter { (id, at) -> present[id] in ticked && now - at < ARM_WINDOW_MS }
        val connected = present.values.joinToString().ifEmpty { "no Bluetooth audio" }
        armed.clear()
        if (fresh.isEmpty()) {
            log("Play from ${controller.packageName}: allowed ($connected)")
            return null
        }
        val (id, at) = fresh.entries.minBy { it.value }
        val reason = "automatic Play ${(now - at) / 1000} s after ${present[id]} connected"
        log("Play from ${controller.packageName}: ignored ($reason)")
        return reason
    }

    private fun isOwnUi(controller: MediaSession.ControllerInfo) =
        controller.uid == ownUid && controller.connectionHints.getBoolean(PlaybackClient.HINT_OWN_UI, false)

    companion object {
        /** How long after a connection its first outside Play still counts as the car's automatic one. */
        const val ARM_WINDOW_MS = 2 * 60_000L

        // Plain int constants compared against what the platform reports; older Android never reports the BLE ones.
        @SuppressLint("InlinedApi")
        private val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )

        /** No permission needed: the product name is the device's Bluetooth name ("Sony CAR AUDIO"). */
        private fun AudioManager.bluetoothOutputNames(): Map<Int, String> =
            getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in BLUETOOTH_OUTPUTS }
                // Cleaned the same way as the stored names, so the two compare equal.
                .associate { it.id to (AppSettings.cleanDeviceName(it.productName?.toString().orEmpty()) ?: "Bluetooth device") }

        /**
         * A gate that watches Bluetooth audio outputs for the life of the process. Call once, from the main
         * process's Application, so it also covers the time the playback service isn't running.
         */
        fun watching(
            context: Context,
            ignoreAutoPlayFrom: () -> Set<String>,
            onDeviceSeen: (String) -> Unit,
            log: (String) -> Unit = {},
        ): BluetoothPlayGate {
            val audio = context.getSystemService(AudioManager::class.java)
            val gate = BluetoothPlayGate(
                bluetoothOutputs = { audio.bluetoothOutputNames() },
                ignoreAutoPlayFrom = ignoreAutoPlayFrom,
                onDeviceSeen = onDeviceSeen,
                log = log,
            )
            audio.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        val ids = addedDevices.filter { it.type in BLUETOOTH_OUTPUTS }.map { it.id }.toSet()
                        if (ids.isNotEmpty()) gate.onBluetoothOutputsAdded(ids)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            return gate
        }
    }
}
