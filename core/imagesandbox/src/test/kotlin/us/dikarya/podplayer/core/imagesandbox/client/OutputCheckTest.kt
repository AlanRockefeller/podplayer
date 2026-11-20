/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.client

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.TestImages
import us.dikarya.podplayer.core.imagesandbox.TestImages.bytes

/**
 * The app's check of what the sandbox sends back. A sandbox taken over by a hostile image could send anything,
 * so the app parses the structure itself (no decoding), keeps only the parts a plain image needs, and refuses
 * the rest before anything is cached or shown.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutputCheckTest {
    private fun valid(bytes: ByteArray): OutputCheck.Result.Valid {
        val result = OutputCheck.inspect(bytes)
        assertTrue("expected valid, got $result", result is OutputCheck.Result.Valid)
        return result as OutputCheck.Result.Valid
    }

    private fun invalid(bytes: ByteArray) {
        val result = OutputCheck.inspect(bytes)
        assertTrue("expected invalid, got $result", result is OutputCheck.Result.Invalid)
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun platformEncodedPngAndJpegPass() {
        val png = valid(TestImages.png(300, 200, alpha = true))
        assertEquals(OutputFormat.Png, png.format)
        assertEquals(300 to 200, png.width to png.height)
        assertEquals(300 to 200, boundsOf(png.bytes))

        val jpeg = valid(TestImages.jpeg(1024, 700))
        assertEquals(OutputFormat.Jpeg, jpeg.format)
        assertEquals(1024 to 700, jpeg.width to jpeg.height)
        assertEquals(1024 to 700, boundsOf(jpeg.bytes))
    }

    @Test
    fun anythingLargerThanTheOutputSizeIsRefused() {
        invalid(TestImages.handMadePng(1025, 1, rows = 1))
        invalid(TestImages.handMadePng(20_000, 20_000, rows = 20_000))
        invalid(TestImages.jpeg(1025, 10))
        valid(TestImages.handMadePng(1024, 1024, rows = 1024))
    }

    @Test
    fun pngExtrasAreStrippedAndUnknownCriticalChunksRefused() {
        val png = TestImages.png(40, 30)
        val withText = insertPngChunk(png, "tEXt", "Comment".toByteArray() + ByteArray(1) + "hello".toByteArray())
        val withIcc = insertPngChunk(png, "iCCP", "p".toByteArray() + ByteArray(30))
        for (sample in listOf(withText, withIcc)) {
            val cleaned = valid(sample).bytes
            assertFalse(String(cleaned, Charsets.ISO_8859_1).contains("tEXt"))
            assertFalse(String(cleaned, Charsets.ISO_8859_1).contains("iCCP"))
            assertEquals(40 to 30, boundsOf(cleaned))
        }
        invalid(insertPngChunk(png, "ABCD", ByteArray(4)))
    }

    @Test
    fun brokenPngStructureIsRefused() {
        val png = TestImages.png(40, 30)
        invalid(png.copyOf(png.size - 1))
        invalid(png.copyOf(png.size / 2))
        invalid(png + bytes(0, 0, 0, 0))
        invalid(png.copyOf().also { it[png.size - 20] = (it[png.size - 20] + 1).toByte() }) // CRC mismatch
        invalid(TestImages.handMadePng(10, 10, rows = 4)) // no IEND
        invalid(TestImages.PNG_SIGNATURE)
        // IHDR not first.
        invalid(insertPngChunk(png, "IHDR", png.copyOfRange(16, 29), afterSignature = true))
        // Interlace method 2 doesn't exist.
        invalid(withIhdrByte(png, 12, 2))
        // Bit depth 3 doesn't exist.
        invalid(withIhdrByte(png, 8, 3))
    }

    @Test
    fun jpegExtrasAreStripped() {
        val jpeg = TestImages.jpeg(64, 48)
        val exif = bytes(0xFF, 0xE1) + u16(2 + 14) + "Exif".toByteArray() + ByteArray(10)
        val comment = bytes(0xFF, 0xFE) + u16(2 + 5) + "hello".toByteArray()
        val sample = jpeg.copyOfRange(0, 2) + exif + comment + jpeg.copyOfRange(2, jpeg.size)
        val cleaned = valid(sample).bytes
        assertFalse(String(cleaned, Charsets.ISO_8859_1).contains("Exif"))
        assertFalse(String(cleaned, Charsets.ISO_8859_1).contains("hello"))
        assertEquals(64 to 48, boundsOf(cleaned))
    }

    @Test
    fun brokenJpegStructureIsRefused() {
        val jpeg = TestImages.jpeg(64, 48)
        invalid(jpeg.copyOf(jpeg.size - 2)) // no EOI
        invalid(jpeg.copyOf(jpeg.size / 2))
        invalid(jpeg + bytes(0x00))
        invalid(TestImages.jpegHeaderOnly(100, 100)) // no scan
        val sof = indexOfMarker(jpeg, 0xC0)
        // A progressive or lossless frame is not what the sandbox writes.
        invalid(jpeg.copyOf().also { it[sof + 1] = 0xC2.toByte() })
        invalid(jpeg.copyOf().also { it[sof + 1] = 0xC3.toByte() })
        // A frame claiming 2,000 px.
        invalid(jpeg.copyOf().also { it[sof + 5] = 0x07; it[sof + 6] = 0xD0.toByte() })
        // A marker in the middle of the entropy-coded data.
        val scanEnd = jpeg.size - 2
        invalid(jpeg.copyOfRange(0, scanEnd - 4) + bytes(0xFF, 0xC4) + jpeg.copyOfRange(scanEnd - 4, jpeg.size))
    }

    @Test
    fun otherFormatsAndNoiseAreRefused() {
        invalid(TestImages.animatedGif)
        invalid(TestImages.svg)
        invalid(TestImages.webp())
        invalid(TestImages.random(1000))
        invalid(ByteArray(0))
        invalid(bytes(0xFF, 0xD8))
    }

    @Test
    fun aValidImageComesBackUnchangedApartFromExtras() {
        val png = TestImages.handMadePng(16, 16, rows = 16)
        assertArrayEquals(png, valid(png).bytes)
    }

    private fun u16(value: Int) = bytes(value shr 8, value and 0xFF)

    private fun insertPngChunk(png: ByteArray, type: String, data: ByteArray, afterSignature: Boolean = false): ByteArray {
        val at = if (afterSignature) 8 else 8 + 25 // after the signature, or after IHDR
        val chunk = ByteArrayOutputStream().also { TestImages.chunk(it, type, data) }.toByteArray()
        return png.copyOfRange(0, at) + chunk + png.copyOfRange(at, png.size)
    }

    /** Changes one IHDR data byte and fixes the CRC, so only that field is wrong. */
    private fun withIhdrByte(png: ByteArray, offset: Int, value: Int): ByteArray {
        val data = png.copyOfRange(16, 29).also { it[offset] = value.toByte() }
        val chunk = ByteArrayOutputStream().also { TestImages.chunk(it, "IHDR", data) }.toByteArray()
        return png.copyOfRange(0, 8) + chunk + png.copyOfRange(33, png.size)
    }

    private fun indexOfMarker(jpeg: ByteArray, marker: Int): Int =
        (0 until jpeg.size - 1).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == marker.toByte() }
}
