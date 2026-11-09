/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.core.imagesandbox.TestImages.bytes
import us.dikarya.podplayer.core.net.BodyLimit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageFormatTest {
    @Test
    fun recognisesTheFormatsWeTakeByTheirBytesNotTheirLabel() {
        assertEquals(Sniff.Image(SourceFormat.Png), ImageSniffer.sniff(TestImages.png()))
        assertEquals(Sniff.Image(SourceFormat.Jpeg), ImageSniffer.sniff(TestImages.jpeg()))
        assertEquals(Sniff.Image(SourceFormat.WebP), ImageSniffer.sniff(TestImages.webp()))
        assertEquals(Sniff.Image(SourceFormat.Gif), ImageSniffer.sniff(TestImages.animatedGif))
        assertEquals(Sniff.Image(SourceFormat.Gif), ImageSniffer.sniff("GIF87a".toByteArray() + ByteArray(10)))
    }

    @Test
    fun svgIsRecognisedHoweverItStarts() {
        assertEquals(Sniff.Svg, ImageSniffer.sniff(TestImages.svg))
        assertEquals(Sniff.Svg, ImageSniffer.sniff(TestImages.svgWithProlog))
        // Gzipped SVG (svgz) and any other compressed blob.
        assertEquals(Sniff.Unsupported, ImageSniffer.sniff(bytes(0x1F, 0x8B, 0x08, 0x00, 0, 0, 0, 0)))
    }

    @Test
    fun otherImageFormatsAreUnsupported() {
        val heic = bytes(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(16)
        val avif = bytes(0, 0, 0, 0x1C) + "ftypavif".toByteArray() + ByteArray(16)
        val bmp = "BM".toByteArray() + ByteArray(30)
        val ico = bytes(0, 0, 1, 0, 1, 0) + ByteArray(20)
        val tiff = bytes(0x49, 0x49, 0x2A, 0x00) + ByteArray(20)
        for (sample in listOf(heic, avif, bmp, ico, tiff)) {
            assertEquals(Sniff.Unsupported, ImageSniffer.sniff(sample))
        }
    }

    @Test
    fun webPagesTextAndNoiseAreNotImages() {
        assertEquals(Sniff.Unknown, ImageSniffer.sniff(TestImages.html))
        assertEquals(Sniff.Unknown, ImageSniffer.sniff("Not Found".toByteArray()))
        assertEquals(Sniff.Unknown, ImageSniffer.sniff(TestImages.random(500)))
        // A truncated signature.
        assertEquals(Sniff.Unknown, ImageSniffer.sniff(TestImages.PNG_SIGNATURE.copyOf(5)))
        assertEquals(Sniff.Unknown, ImageSniffer.sniff("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()))
        assertEquals(Sniff.Empty, ImageSniffer.sniff(ByteArray(0)))
    }

    @Test
    fun headerSizesAreReadWithoutDecoding() {
        assertEquals(300 to 200, ImageHeaders.size(TestImages.png(300, 200), SourceFormat.Png))
        assertEquals(301 to 199, ImageHeaders.size(TestImages.jpeg(301, 199), SourceFormat.Jpeg))
        assertEquals(1 to 1, ImageHeaders.size(TestImages.animatedGif, SourceFormat.Gif))
        assertEquals(300 to 200, ImageHeaders.size(TestImages.webp(300, 200), SourceFormat.WebP))
        assertEquals(
            320 to 240,
            ImageHeaders.size(TestImages.encoded(320, 240, android.graphics.Bitmap.CompressFormat.WEBP_LOSSY), SourceFormat.WebP),
        )
        assertEquals(20_000 to 20_000, ImageHeaders.size(TestImages.handMadePng(20_000, 20_000, rows = 2), SourceFormat.Png))
        assertEquals(65_000 to 64_000, ImageHeaders.size(TestImages.jpegHeaderOnly(65_000, 64_000), SourceFormat.Jpeg))
    }

    @Test
    fun brokenHeadersHaveNoSize() {
        assertEquals(null, ImageHeaders.size(TestImages.PNG_SIGNATURE + TestImages.random(40), SourceFormat.Png))
        assertEquals(null, ImageHeaders.size(TestImages.png().copyOf(20), SourceFormat.Png))
        assertEquals(null, ImageHeaders.size(bytes(0xFF, 0xD8, 0xFF, 0xDA, 0x00, 0x02), SourceFormat.Jpeg))
        assertEquals(null, ImageHeaders.size(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0xFF, 0xFF), SourceFormat.Jpeg))
        assertEquals(null, ImageHeaders.size("GIF89a".toByteArray(), SourceFormat.Gif))
        assertEquals(null, ImageHeaders.size("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8Z".toByteArray() + ByteArray(20), SourceFormat.WebP))
        for (format in SourceFormat.entries) {
            for (seed in 1..200) ImageHeaders.size(TestImages.random(seed % 64, seed), format) // never throws
        }
    }

    @Test
    fun theSandboxInputLimitIsTheNetworkImageLimit() {
        assertEquals(BodyLimit.IMAGE.maxBytes, SandboxLimits.MAX_INPUT_BYTES.toLong())
        assertEquals(8_000, SandboxLimits.MAX_SOURCE_SIDE)
        assertEquals(1_024, SandboxLimits.MAX_OUTPUT_SIDE)
    }

    @Test
    fun refusalCodesRoundTripAndAreNegative() {
        for (refusal in Refusal.entries) {
            assert(refusal.code < 0)
            assertEquals(refusal, Refusal.fromCode(refusal.code))
        }
        assertEquals(null, Refusal.fromCode(-999))
        assertEquals(null, Refusal.fromCode(5))
    }
}
