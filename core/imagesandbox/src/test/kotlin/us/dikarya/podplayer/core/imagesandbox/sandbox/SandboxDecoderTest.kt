/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.sandbox

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.system.measureTimeMillis
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
import us.dikarya.podplayer.core.imagesandbox.TestImages.bytes

/**
 * The decoder that runs inside the isolated process, with the real platform decoders (Robolectric native
 * graphics). SPEC §6 and the image items of the SPEC §14 hostile corpus.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SandboxDecoderTest {
    /** The decoder as it runs in the sandbox process. */
    private fun decode(bytes: ByteArray): SandboxDecoder.Outcome {
        ShadowProcess.setIsIsolated()
        return SandboxDecoder().decode(bytes, SandboxLimits.MAX_OUTPUT_SIDE)
    }

    private fun encoded(bytes: ByteArray): SandboxDecoder.Outcome.Encoded {
        val outcome = decode(bytes)
        assertTrue("expected an image, got $outcome", outcome is SandboxDecoder.Outcome.Encoded)
        return outcome as SandboxDecoder.Outcome.Encoded
    }

    private fun refusal(bytes: ByteArray): Refusal? = (decode(bytes) as? SandboxDecoder.Outcome.Refused)?.refusal

    /** Checks what the sandbox wrote really is what it says (decoding here is fine: this is a test). */
    private fun checkOutput(out: SandboxDecoder.Outcome.Encoded, width: Int, height: Int, format: OutputFormat) {
        assertEquals(format, out.format)
        assertEquals(width, out.width)
        assertEquals(height, out.height)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size, options)
        assertEquals(width, options.outWidth)
        assertEquals(height, options.outHeight)
        assertEquals(format.mimeType, options.outMimeType)
    }

    @Test
    fun anImageWithAlphaComesBackAsPng() {
        val out = encoded(TestImages.png(300, 200, alpha = true))
        checkOutput(out, 300, 200, OutputFormat.Png)
        val bitmap = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
    }

    @Test
    fun anOpaqueImageComesBackAsJpegScaledToAtMost1024() {
        checkOutput(encoded(TestImages.jpeg(3000, 2000)), 1024, 683, OutputFormat.Jpeg)
        checkOutput(encoded(TestImages.jpeg(500, 4000)), 128, 1024, OutputFormat.Jpeg)
    }

    @Test
    fun smallImagesKeepTheirSize() {
        checkOutput(encoded(TestImages.jpeg(200, 100)), 200, 100, OutputFormat.Jpeg)
    }

    @Test
    fun webPIsDecoded() {
        val out = encoded(TestImages.webp(300, 200))
        assertEquals(300, out.width)
        assertEquals(200, out.height)
    }

    @Test
    fun animatedGifGivesItsFirstFrame() {
        val out = encoded(TestImages.animatedGif)
        assertEquals(1, out.width)
        val pixel = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size).getPixel(0, 0)
        assertTrue("first frame is red, got ${Integer.toHexString(pixel)}", Color.red(pixel) > 200 && Color.blue(pixel) < 60)
    }

    @Test
    fun truncatedImagesAreRefused() {
        val png = TestImages.png(300, 200)
        val jpeg = TestImages.jpeg(300, 200)
        assertEquals(Refusal.Unreadable, refusal(png.copyOf(png.size / 2)))
        assertEquals(Refusal.Unreadable, refusal(jpeg.copyOf(jpeg.size / 2)))
        assertEquals(Refusal.Unreadable, refusal(png.copyOf(40)))
        // The hand-made PNG without its last rows and IEND.
        assertEquals(Refusal.Unreadable, refusal(TestImages.handMadePng(64, 64, rows = 10)))
    }

    @Test
    fun oversizedHeadersAreRefusedBeforeDecoding() {
        assertEquals(Refusal.TooLarge, refusal(TestImages.handMadePng(9_000, 10, rows = 10)))
        assertEquals(Refusal.TooLarge, refusal(TestImages.handMadePng(10, 8_001, rows = 8_001)))
        assertEquals(Refusal.TooLarge, refusal(TestImages.jpegHeaderOnly(65_000, 65_000)))
    }

    @Test
    fun aDecompressionBombIsRefusedQuickly() {
        // 20,000 x 20,000 pixels (400 megapixels) in a few kilobytes.
        val bomb = TestImages.handMadePng(20_000, 20_000, rows = 20_000)
        assertTrue("bomb is ${bomb.size} bytes", bomb.size < 200_000)
        val took = measureTimeMillis { assertEquals(Refusal.TooLarge, refusal(bomb)) }
        assertTrue("took $took ms", took < 2_000)
    }

    @Test
    fun aBombInsideTheLimitsIsDecodedAtTheTargetSizeOnly() {
        // 8,000 x 8,000 is allowed; it must come out at 1,024 x 1,024, not 64 megapixels.
        val big = TestImages.handMadePng(8_000, 8_000, rows = 8_000)
        checkOutput(encoded(big), 1024, 1024, OutputFormat.Jpeg)
    }

    @Test
    fun svgIsRefused() {
        assertEquals(Refusal.Svg, refusal(TestImages.svg))
        assertEquals(Refusal.Svg, refusal(TestImages.svgWithProlog))
    }

    @Test
    fun thingsThatAreNotImagesAreRefused() {
        assertEquals(Refusal.NotAnImage, refusal(TestImages.html))
        assertEquals(Refusal.NotAnImage, refusal(TestImages.random(4096)))
        assertEquals(Refusal.Empty, refusal(ByteArray(0)))
        val heic = bytes(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(64)
        assertEquals(Refusal.UnsupportedFormat, refusal(heic))
    }

    @Test
    fun aRealSignatureOnGarbageIsRefused() {
        assertEquals(Refusal.Unreadable, refusal(TestImages.PNG_SIGNATURE + TestImages.random(2000)))
        for (seed in 1..20) {
            // Random bytes may happen to "declare" a huge size; either way they are refused.
            val jpeg = refusal(bytes(0xFF, 0xD8, 0xFF) + TestImages.random(2000, seed))
            val gif = refusal("GIF89a".toByteArray() + TestImages.random(100, seed))
            assertTrue("$jpeg", jpeg == Refusal.Unreadable || jpeg == Refusal.TooLarge)
            assertTrue("$gif", gif == Refusal.Unreadable || gif == Refusal.TooLarge)
        }
    }

    @Test
    fun tooManyBytesAreRefusedWithoutDecoding() {
        val big = TestImages.png(10, 10) + ByteArray(SandboxLimits.MAX_INPUT_BYTES)
        assertEquals(Refusal.TooManyBytes, refusal(big))
    }

    @Test
    fun theDecoderRefusesToRunOutsideAnIsolatedProcess() {
        // No ShadowProcess.setIsIsolated(): this is what the app process would see.
        val outcome = SandboxDecoder().decode(TestImages.png(), SandboxLimits.MAX_OUTPUT_SIDE)
        assertEquals(SandboxDecoder.Outcome.Refused(Refusal.NotIsolated), outcome)
    }

    @Test
    fun theOutputSideCanBeSmallerButNeverLarger() {
        ShadowProcess.setIsIsolated()
        val small = SandboxDecoder().decode(TestImages.jpeg(800, 400), 200) as SandboxDecoder.Outcome.Encoded
        assertEquals(200, small.width)
        assertEquals(100, small.height)
        assertEquals(
            SandboxDecoder.Outcome.Refused(Refusal.BadRequest),
            SandboxDecoder().decode(TestImages.jpeg(), SandboxLimits.MAX_OUTPUT_SIDE + 1),
        )
        assertEquals(SandboxDecoder.Outcome.Refused(Refusal.BadRequest), SandboxDecoder().decode(TestImages.jpeg(), 0))
    }
}
