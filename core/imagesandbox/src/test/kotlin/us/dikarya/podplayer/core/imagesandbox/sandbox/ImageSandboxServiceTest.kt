/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.sandbox

import android.content.Intent
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.Refusal
import us.dikarya.podplayer.core.imagesandbox.SandboxLimits
import us.dikarya.podplayer.core.imagesandbox.TestImages
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox

/** The sandbox service through its binder interface, with shared memory as the app passes it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSandboxServiceTest {
    private fun service(): IImageSandbox {
        ShadowProcess.setIsIsolated()
        val binder = Robolectric.setupService(ImageSandboxService::class.java).onBind(Intent())
        return IImageSandbox.Stub.asInterface(binder)
    }

    private fun input(bytes: ByteArray): SharedMemory = SharedMemory.create("in", maxOf(bytes.size, 1)).also { memory ->
        val map = memory.mapReadWrite()
        map.put(bytes)
        SharedMemory.unmap(map)
    }

    private fun read(memory: SharedMemory, length: Int): ByteArray {
        val map = memory.mapReadOnly()
        return ByteArray(length).also { map.get(it) }.also { SharedMemory.unmap(map) }
    }

    @Test
    fun aGoodImageRoundTripsThroughSharedMemory() {
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        val length = service().decode(input(TestImages.jpeg(2048, 1024)), output, SandboxLimits.MAX_OUTPUT_SIDE)
        assertTrue("length $length", length > 0)
        val bytes = read(output, length)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(OutputFormat.Jpeg.mimeType, mimeOf(bytes))
    }

    @Test
    fun refusalsComeBackAsNegativeCodes() {
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        val sandbox = service()
        assertEquals(Refusal.Svg.code, sandbox.decode(input(TestImages.svg), output, 1024))
        assertEquals(Refusal.NotAnImage.code, sandbox.decode(input(TestImages.html), output, 1024))
        val bomb = TestImages.handMadePng(20_000, 20_000, rows = 20_000)
        assertEquals(Refusal.TooLarge.code, sandbox.decode(input(bomb), output, 1024))
        assertEquals(Refusal.BadRequest.code, sandbox.decode(null, output, 1024))
        assertEquals(Refusal.BadRequest.code, sandbox.decode(input(TestImages.png()), null, 1024))
    }

    @Test
    fun anOversizedInputIsRefusedWithoutReadingIt() {
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        val big = SharedMemory.create("in", SandboxLimits.MAX_INPUT_BYTES + 1)
        assertEquals(Refusal.TooManyBytes.code, service().decode(big, output, 1024))
    }

    @Test
    fun outputThatDoesNotFitTheRegionIsRefused() {
        val tiny = SharedMemory.create("out", 64)
        assertEquals(Refusal.OutputTooLarge.code, service().decode(input(TestImages.jpeg()), tiny, 1024))
    }

    @Test
    fun theServiceRefusesOutsideAnIsolatedProcess() {
        val binder = Robolectric.setupService(ImageSandboxService::class.java).onBind(Intent())
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        val code = IImageSandbox.Stub.asInterface(binder).decode(input(TestImages.png()), output, 1024)
        assertEquals(Refusal.NotIsolated.code, code)
    }

    @Test
    fun aDecodeThatTakesTooLongKillsTheSandboxProcess() {
        ShadowProcess.setIsIsolated()
        val killed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binder = SandboxBinder(
            decoder = { _, _ ->
                release.await(10, TimeUnit.SECONDS)
                SandboxDecoder.Outcome.Refused(Refusal.Unreadable)
            },
            timeLimitMs = 100,
            kill = { killed.countDown() },
        )
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        val worker = Thread { binder.decode(input(TestImages.png()), output, 1024) }
        worker.start()
        assertTrue("watchdog fired", killed.await(5, TimeUnit.SECONDS))
        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun aQuickDecodeDoesNotTripTheWatchdog() {
        ShadowProcess.setIsIsolated()
        var kills = 0
        val binder = SandboxBinder(decoder = SandboxDecoder()::decode, timeLimitMs = 2_000, kill = { kills++ })
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        repeat(3) { assertTrue(binder.decode(input(TestImages.png()), output, 1024) > 0) }
        Thread.sleep(2_500)
        assertEquals(0, kills)
    }

    @Test
    fun anExceptionInTheDecoderIsARefusalNotACrash() {
        ShadowProcess.setIsIsolated()
        val binder = SandboxBinder(decoder = { _, _ -> error("boom") }, timeLimitMs = 1_000, kill = {})
        val output = SharedMemory.create("out", SandboxLimits.OUTPUT_REGION_BYTES)
        assertEquals(Refusal.Unreadable.code, binder.decode(input(TestImages.png()), output, 1024))
    }

    private fun mimeOf(bytes: ByteArray): String? {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outMimeType
    }
}
