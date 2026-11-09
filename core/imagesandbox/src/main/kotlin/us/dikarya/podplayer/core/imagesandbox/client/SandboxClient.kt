/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.client

import android.os.SharedMemory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import us.dikarya.podplayer.core.imagesandbox.Refusal
import us.dikarya.podplayer.core.imagesandbox.SandboxLimits
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox

/** What came back from the sandbox. Never an exception. */
sealed interface SandboxResult {
    /** Decoded, re-encoded, and passed [OutputCheck]. */
    class Decoded(val image: OutputCheck.Result.Valid) : SandboxResult {
        override fun toString() = "Decoded($image)"
    }

    data class Refused(val refusal: Refusal) : SandboxResult

    /** The sandbox process died or threw. */
    data object Crashed : SandboxResult

    /** No answer within the time limit (5 s, SPEC §6). */
    data object TimedOut : SandboxResult

    /** The sandbox couldn't be started. */
    data object Unavailable : SandboxResult

    /** The sandbox answered with something that isn't a plain image of the expected size. */
    data class BadOutput(val problem: String) : SandboxResult
}

/** Decodes untrusted image bytes somewhere safe. The app's only implementation is [SandboxClient]. */
fun interface ImageDecodeSandbox {
    suspend fun decode(bytes: ByteArray): SandboxResult
}

/** Counts for the Developer screen. */
data class SandboxStats(
    val decoded: Int = 0,
    val refused: Int = 0,
    val crashed: Int = 0,
    val timedOut: Int = 0,
    val unavailable: Int = 0,
    val badOutput: Int = 0,
)

/** Binds to the sandbox service. Separate from [SandboxClient] so tests can hand it sandboxes directly. */
internal interface SandboxConnector {
    /** A live sandbox, starting one if needed; null when it can't be bound. */
    suspend fun connect(): IImageSandbox?

    /** Drops the sandbox. Once nothing is bound, Android kills the isolated process; [connect] starts afresh. */
    fun disconnect()
}

/**
 * The app's side of the image sandbox (SPEC §6). One request at a time. Each one copies the bytes into a new
 * shared-memory region, gives the sandbox a fixed-size output region, and waits at most [timeout] for binding
 * and decoding together. A sandbox that dies, throws or hangs becomes [SandboxResult.Crashed] or
 * [SandboxResult.TimedOut] and is dropped, so the next request gets a fresh process. The answer is copied out
 * of shared memory before [OutputCheck] looks at it, so the sandbox can't change it afterwards.
 */
class SandboxClient internal constructor(
    private val connector: SandboxConnector,
    private val timeout: Duration = 5.seconds,
    private val idleTimeout: Duration = 30.seconds,
    private val scope: CoroutineScope,
) : ImageDecodeSandbox {
    private val mutex = Mutex()
    private var idleJob: Job? = null

    /**
     * Binder calls block their thread, and a hung sandbox can't be interrupted. They run here, outside the
     * caller's coroutine, so a timeout returns at once; the thread is freed when the dropped sandbox dies.
     */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stats = MutableStateFlow(SandboxStats())
    val stats: StateFlow<SandboxStats> = _stats.asStateFlow()

    override suspend fun decode(bytes: ByteArray): SandboxResult {
        if (bytes.isEmpty()) return count(SandboxResult.Refused(Refusal.Empty))
        if (bytes.size > SandboxLimits.MAX_INPUT_BYTES) return count(SandboxResult.Refused(Refusal.TooManyBytes))
        return mutex.withLock {
            idleJob?.cancel()
            val result = withTimeoutOrNull(timeout) { attempt(bytes) } ?: SandboxResult.TimedOut
            if (result == SandboxResult.Crashed || result == SandboxResult.TimedOut) connector.disconnect()
            idleJob = scope.launch {
                delay(idleTimeout)
                mutex.withLock { connector.disconnect() }
            }
            count(result)
        }
    }

    private suspend fun attempt(bytes: ByteArray): SandboxResult {
        val sandbox = connector.connect() ?: return SandboxResult.Unavailable
        val input: SharedMemory
        val output: SharedMemory
        try {
            input = SharedMemory.create("artwork-in", bytes.size)
            output = try {
                SharedMemory.create("artwork-out", SandboxLimits.OUTPUT_REGION_BYTES)
            } catch (e: Exception) {
                input.close()
                throw e
            }
        } catch (_: Exception) {
            return SandboxResult.Unavailable
        }
        try {
            val map = input.mapReadWrite()
            try {
                map.put(bytes)
            } finally {
                SharedMemory.unmap(map)
            }
            val call = calls.async { sandbox.decode(input, output, SandboxLimits.MAX_OUTPUT_SIDE) }
            val length = try {
                call.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return SandboxResult.Crashed
            }
            if (length < 0) {
                return Refusal.fromCode(length)?.let { SandboxResult.Refused(it) }
                    ?: SandboxResult.BadOutput("unknown refusal code")
            }
            if (length == 0 || length > output.size) return SandboxResult.BadOutput("output length out of range")
            val copy = ByteArray(length)
            val answer = output.mapReadOnly()
            try {
                answer.get(copy)
            } finally {
                SharedMemory.unmap(answer)
            }
            return when (val checked = OutputCheck.inspect(copy)) {
                is OutputCheck.Result.Valid -> SandboxResult.Decoded(checked)
                is OutputCheck.Result.Invalid -> SandboxResult.BadOutput(checked.problem)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SandboxResult.Unavailable
        } finally {
            input.close()
            output.close()
        }
    }

    private fun count(result: SandboxResult): SandboxResult {
        _stats.update {
            when (result) {
                is SandboxResult.Decoded -> it.copy(decoded = it.decoded + 1)
                is SandboxResult.Refused -> it.copy(refused = it.refused + 1)
                SandboxResult.Crashed -> it.copy(crashed = it.crashed + 1)
                SandboxResult.TimedOut -> it.copy(timedOut = it.timedOut + 1)
                SandboxResult.Unavailable -> it.copy(unavailable = it.unavailable + 1)
                is SandboxResult.BadOutput -> it.copy(badOutput = it.badOutput + 1)
            }
        }
        return result
    }
}
