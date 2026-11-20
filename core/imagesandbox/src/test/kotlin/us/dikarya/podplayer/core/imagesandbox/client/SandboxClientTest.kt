/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.client

import android.os.DeadObjectException
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.Refusal
import us.dikarya.podplayer.core.imagesandbox.SandboxLimits
import us.dikarya.podplayer.core.imagesandbox.TestImages
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox
import us.dikarya.podplayer.core.imagesandbox.sandbox.SandboxBinder
import us.dikarya.podplayer.core.imagesandbox.sandbox.SandboxDecoder

/**
 * The app side of the process boundary. "A sandbox crash does not crash the app": every way the sandbox can
 * fail (dying, throwing, hanging, refusing to bind, answering nonsense) ends as a result value.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SandboxClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun client(connector: FakeConnector, timeout: kotlin.time.Duration = 5.seconds, idle: kotlin.time.Duration = 30.seconds) =
        SandboxClient(connector, timeout = timeout, idleTimeout = idle, scope = scope)

    /** The real sandbox binder, as if it ran in the isolated process. */
    private fun realSandbox(): IImageSandbox {
        ShadowProcess.setIsIsolated()
        return SandboxBinder(SandboxDecoder()::decode, timeLimitMs = 4_000, kill = {})
    }

    @Test
    fun aGoodImageComesBackCheckedAndOnlyItsBytesCrossTheBoundary() = runBlocking<Unit> {
        val seen = mutableListOf<ByteArray>()
        val recording = object : IImageSandbox.Stub() {
            override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int): Int {
                val map = input!!.mapReadOnly()
                seen += ByteArray(map.remaining()).also { map.get(it) }
                SharedMemory.unmap(map)
                assertEquals(SandboxLimits.MAX_OUTPUT_SIDE, maxSidePx)
                assertEquals(SandboxLimits.OUTPUT_REGION_BYTES, output!!.size)
                return realSandbox().decode(input, output, maxSidePx)
            }
        }
        val sent = TestImages.jpeg(2000, 1000)
        val result = client(FakeConnector { recording }).decode(sent)
        assertArrayEquals(sent, seen.single())
        result as SandboxResult.Decoded
        assertEquals(OutputFormat.Jpeg, result.image.format)
        assertEquals(1024 to 512, result.image.width to result.image.height)
    }

    @Test
    fun refusalsAreResultsAndCounted() = runBlocking<Unit> {
        val client = client(FakeConnector { realSandbox() })
        assertEquals(SandboxResult.Refused(Refusal.Svg), client.decode(TestImages.svg))
        assertEquals(SandboxResult.Refused(Refusal.TooLarge), client.decode(TestImages.handMadePng(9_000, 9_000, rows = 9_000)))
        assertEquals(2, client.stats.value.refused)
    }

    @Test
    fun emptyAndOversizedInputNeverReachTheSandbox() = runBlocking<Unit> {
        val connector = FakeConnector { error("must not connect") }
        val client = client(connector)
        assertEquals(SandboxResult.Refused(Refusal.Empty), client.decode(ByteArray(0)))
        assertEquals(SandboxResult.Refused(Refusal.TooManyBytes), client.decode(ByteArray(SandboxLimits.MAX_INPUT_BYTES + 1)))
        assertEquals(0, connector.connects)
    }

    @Test
    fun aSandboxThatDiesIsACrashResultAndTheNextRequestStartsAFreshOne() = runBlocking<Unit> {
        var calls = 0
        val connector = FakeConnector {
            calls++
            if (calls == 1) stub { throw DeadObjectException() } else realSandbox()
        }
        val client = client(connector)
        assertEquals(SandboxResult.Crashed, client.decode(TestImages.png()))
        assertEquals(1, connector.disconnects)
        assertTrue(client.decode(TestImages.png()) is SandboxResult.Decoded)
        assertEquals(2, connector.connects)
        assertEquals(1, client.stats.value.crashed)
        assertEquals(1, client.stats.value.decoded)
    }

    @Test
    fun anyExceptionFromTheSandboxIsACrashResult() = runBlocking<Unit> {
        for (failure in listOf(RuntimeException("x"), SecurityException("x"), IllegalStateException("x"), DeadObjectException())) {
            val client = client(FakeConnector { stub { throw failure } })
            assertEquals(failure.toString(), SandboxResult.Crashed, client.decode(TestImages.png()))
        }
    }

    @Test
    fun aHangingSandboxTimesOutAndIsDropped() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        var calls = 0
        val connector = FakeConnector {
            calls++
            if (calls == 1) stub { release.await(10, TimeUnit.SECONDS); 1 } else realSandbox()
        }
        val client = client(connector, timeout = 300.milliseconds)
        val took = measureTimeMillis { assertEquals(SandboxResult.TimedOut, client.decode(TestImages.png())) }
        assertTrue("took $took ms", took < 2_000)
        assertEquals(1, connector.disconnects)
        assertTrue(client.decode(TestImages.png()) is SandboxResult.Decoded)
        release.countDown()
        assertEquals(1, client.stats.value.timedOut)
    }

    @Test
    fun aSandboxThatCannotBeBoundIsUnavailableOrTimesOut() = runBlocking<Unit> {
        assertEquals(SandboxResult.Unavailable, client(FakeConnector { null }).decode(TestImages.png()))
        val slow = FakeConnector(connectDelayMs = 5_000) { realSandbox() }
        assertEquals(SandboxResult.TimedOut, client(slow, timeout = 200.milliseconds).decode(TestImages.png()))
    }

    @Test
    fun nonsenseAnswersAreRefused() = runBlocking<Unit> {
        for (answer in listOf(0, -999, SandboxLimits.OUTPUT_REGION_BYTES + 1, Int.MAX_VALUE)) {
            val result = client(FakeConnector { stub { answer } }).decode(TestImages.png())
            assertTrue("$answer gave $result", result is SandboxResult.BadOutput)
        }
    }

    @Test
    fun hostileOutputIsRefusedByTheCheck() = runBlocking<Unit> {
        val hostile = listOf(
            TestImages.handMadePng(20_000, 20_000, rows = 20_000), // a bomb for the app's own decoder
            TestImages.handMadePng(2_000, 2_000, rows = 2_000), // larger than the sandbox ever writes
            TestImages.svg,
            TestImages.animatedGif,
            TestImages.webp(),
            TestImages.jpeg(64, 64).let { it.copyOf(it.size - 2) },
        )
        for (bytes in hostile) {
            val writesHostile = stub { output ->
                val map = output.mapReadWrite()
                map.put(bytes)
                SharedMemory.unmap(map)
                bytes.size
            }
            val client = client(FakeConnector { writesHostile })
            val result = client.decode(TestImages.png())
            assertTrue("$result", result is SandboxResult.BadOutput)
            assertEquals(1, client.stats.value.badOutput)
        }
    }

    @Test
    fun requestsGoOneAtATime() = runBlocking<Unit> {
        val active = AtomicInteger()
        val most = AtomicInteger()
        val sandbox = realSandbox()
        val counting = object : IImageSandbox.Stub() {
            override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int): Int {
                most.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                Thread.sleep(20)
                return sandbox.decode(input, output, maxSidePx).also { active.decrementAndGet() }
            }
        }
        val client = client(FakeConnector { counting })
        (1..6).map { async(Dispatchers.Default) { client.decode(TestImages.png(40, 40)) } }.awaitAll()
        assertEquals(1, most.get())
    }

    @Test
    fun aCallerThatGivesUpMidDecodeLeavesNoSandboxBound() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val connector = FakeConnector { stub { inside.countDown(); release.await(10, TimeUnit.SECONDS); 1 } }
        val client = client(connector, idle = 100.milliseconds)
        val request = async(Dispatchers.Default) { client.decode(TestImages.png()) }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        request.cancel()
        runCatching { request.await() }
        delay(600)
        release.countDown()
        assertEquals("the sandbox the cancelled request used is let go", 1, connector.disconnects)
    }

    @Test
    fun anIdleSandboxIsReleased() = runBlocking<Unit> {
        val connector = FakeConnector { realSandbox() }
        val client = client(connector, idle = 100.milliseconds)
        assertTrue(client.decode(TestImages.png()) is SandboxResult.Decoded)
        delay(600)
        assertEquals(1, connector.disconnects)
    }

    private fun stub(answer: (SharedMemory) -> Int) = object : IImageSandbox.Stub() {
        override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int): Int = answer(output!!)
    }
}

/** Hands out sandboxes from [next]; counts connects and disconnects. */
internal class FakeConnector(
    private val connectDelayMs: Long = 0,
    private val next: () -> IImageSandbox?,
) : SandboxConnector {
    @Volatile
    private var current: IImageSandbox? = null
    var connects = 0
        private set
    var disconnects = 0
        private set

    override suspend fun connect(): IImageSandbox? {
        if (connectDelayMs > 0) delay(connectDelayMs)
        current?.let { return it }
        connects++
        return next().also { current = it }
    }

    override fun disconnect() {
        disconnects++
        current = null
    }
}
