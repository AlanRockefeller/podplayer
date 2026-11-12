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

/** Decides whether a Play request is obeyed. Null: play. Otherwise the reason it is ignored. */
fun interface PlayGate {
    fun ignoreReason(controller: MediaSession.ControllerInfo?): String?
}

/**
 * CLAUDE.md: never auto-start on Bluetooth connect. Many cars (the owner's included) send Play as soon as they
 * connect, and Android delivers it like a real button press. Every Play reaches the player through the session,
 * which tells us who sent it ([MediaSession.getControllerForCurrentRequest]), so one check covers the lock
 * screen, media buttons, Bluetooth controllers and playback resumption after a cold start.
 *
 * A Play from anything other than our own screens is ignored when Bluetooth audio is connected and either:
 * - a Bluetooth audio output appeared less than [WINDOW_MS] ago, or
 * - the process started less than [COLD_START_WINDOW_MS] ago and we hadn't seen the device appear (the car's
 *   Play is what started us; Android gives no connection time without the Bluetooth permission).
 * A second press after the window plays normally. Our own screens connect with [PlaybackClient.HINT_OWN_UI] from
 * our own uid; the media notification and Media3's media-button caller run in our process but carry no hint.
 */
class BluetoothPlayGate(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val processStartedAt: Long = Process.getStartElapsedRealtime(),
    private val bluetoothOutputConnected: () -> Boolean,
    private val ownUid: Int = Process.myUid(),
) : PlayGate {
    @Volatile
    private var lastBluetoothOutputAt: Long? = null

    fun onBluetoothOutputAdded() {
        lastBluetoothOutputAt = clock()
    }

    override fun ignoreReason(controller: MediaSession.ControllerInfo?): String? {
        if (controller == null || isOwnUi(controller)) return null
        if (!bluetoothOutputConnected()) return null
        val now = clock()
        val added = lastBluetoothOutputAt
        if (added != null) {
            return if (now - added < WINDOW_MS) "${(now - added) / 1000} s after Bluetooth audio connected" else null
        }
        return if (now - processStartedAt < COLD_START_WINDOW_MS) "right after a cold start with Bluetooth audio connected" else null
    }

    private fun isOwnUi(controller: MediaSession.ControllerInfo) =
        controller.uid == ownUid && controller.connectionHints.getBoolean(PlaybackClient.HINT_OWN_UI, false)

    companion object {
        const val WINDOW_MS = 10_000L
        const val COLD_START_WINDOW_MS = 5_000L

        // Plain int constants compared against what the platform reports; older Android never reports the BLE ones.
        @SuppressLint("InlinedApi")
        private val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )

        private fun AudioManager.bluetoothOutputs(): List<AudioDeviceInfo> =
            getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in BLUETOOTH_OUTPUTS }

        /**
         * A gate that watches Bluetooth audio outputs for the life of the process. Call once, from the main
         * process's Application, so it also covers the time the playback service isn't running.
         */
        fun watching(context: Context): BluetoothPlayGate {
            val audio = context.getSystemService(AudioManager::class.java)
            val gate = BluetoothPlayGate(bluetoothOutputConnected = { audio.bluetoothOutputs().isNotEmpty() })
            // Registering reports every device already connected; only devices that appear later count.
            val alreadyThere = audio.bluetoothOutputs().map { it.id }.toSet()
            audio.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        if (addedDevices.any { it.type in BLUETOOTH_OUTPUTS && it.id !in alreadyThere }) gate.onBluetoothOutputAdded()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            return gate
        }
    }
}
