/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import us.dikarya.podplayer.core.imagesandbox.Refusal
import us.dikarya.podplayer.core.imagesandbox.SandboxLimits
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox

/**
 * The image decoder's process (SPEC §6). Declared with `android:isolatedProcess="true"`, not exported, in its
 * own `:imagesandbox` process: it runs under a separate, throwaway user id with no permissions, no network and
 * no access to the app's files. Bytes arrive in shared memory. If a hostile image takes over the decoder, it has
 * nothing to reach, and a crash or a hang here only costs the app a placeholder.
 */
class ImageSandboxService : Service() {
    override fun onBind(intent: Intent?): IBinder = SandboxBinder(
        decoder = SandboxDecoder()::decode,
        timeLimitMs = SandboxLimits.DECODE_TIME_LIMIT_MS,
        kill = { Process.killProcess(Process.myPid()) },
    )
}

/**
 * The binder side of [IImageSandbox]. One decode at a time. A watchdog kills this process when a decode takes
 * longer than [timeLimitMs], so a decoder stuck on a hostile image can't keep running after the app gave up.
 */
internal class SandboxBinder(
    private val decoder: (ByteArray, Int) -> SandboxDecoder.Outcome,
    private val timeLimitMs: Long,
    private val kill: () -> Unit,
) : IImageSandbox.Stub() {
    private val lock = Any()
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "sandbox-watchdog").apply { isDaemon = true }
    }

    override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int): Int = synchronized(lock) {
        try {
            if (input == null || output == null) return Refusal.BadRequest.code
            val size = input.size
            if (size <= 0) return Refusal.Empty.code
            if (size > SandboxLimits.MAX_INPUT_BYTES) return Refusal.TooManyBytes.code
            val bytes = ByteArray(size)
            val mapped = input.mapReadOnly()
            try {
                mapped.get(bytes)
            } finally {
                SharedMemory.unmap(mapped)
            }
            val alarm = watchdog.schedule(kill, timeLimitMs, TimeUnit.MILLISECONDS)
            val outcome = try {
                decoder(bytes, maxSidePx)
            } finally {
                alarm.cancel(false)
            }
            when (outcome) {
                is SandboxDecoder.Outcome.Refused -> outcome.refusal.code
                is SandboxDecoder.Outcome.Encoded -> write(outcome.bytes, output)
            }
        } catch (_: Exception) {
            Refusal.Unreadable.code
        } catch (_: OutOfMemoryError) {
            Refusal.Unreadable.code
        } finally {
            // Across processes these are our own copies of the app's regions: release them now rather than at
            // garbage collection. In an in-process call they are the caller's objects, which it closes itself.
            if (Binder.getCallingPid() != Process.myPid()) {
                input?.close()
                output?.close()
            }
        }
    }

    private fun write(bytes: ByteArray, output: SharedMemory): Int {
        if (bytes.isEmpty()) return Refusal.EncodeFailed.code
        if (bytes.size > output.size) return Refusal.OutputTooLarge.code
        val mapped = output.mapReadWrite()
        try {
            mapped.put(bytes)
        } finally {
            SharedMemory.unmap(mapped)
        }
        return bytes.size
    }
}
