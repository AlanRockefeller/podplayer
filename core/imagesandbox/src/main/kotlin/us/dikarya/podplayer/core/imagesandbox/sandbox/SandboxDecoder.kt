/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.sandbox

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Process
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import us.dikarya.podplayer.core.imagesandbox.ImageHeaders
import us.dikarya.podplayer.core.imagesandbox.ImageSniffer
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.Refusal
import us.dikarya.podplayer.core.imagesandbox.SandboxLimits
import us.dikarya.podplayer.core.imagesandbox.Sniff

/**
 * Decodes one untrusted image and re-encodes it (SPEC §6). This is the only code in the app that hands
 * untrusted bytes to a platform image decoder, and it runs only in the isolated sandbox process: it refuses to
 * work anywhere else, so a mistaken call from the app process fails closed instead of decoding there.
 *
 * Steps: size limit; sniff (PNG, JPEG, GIF, WebP; SVG and everything else refused); the declared size, read
 * from the header bytes by [ImageHeaders], must be at most [SandboxLimits.MAX_SOURCE_SIDE] a side; then
 * `ImageDecoder` reads the header and its header callback checks the size again before any pixel is decoded; decode straight to the target size (at most [SandboxLimits.MAX_OUTPUT_SIDE]), software memory,
 * sRGB, first frame only; an incomplete image is refused rather than shown half-drawn; re-encode as PNG when
 * the image has alpha, JPEG otherwise.
 */
internal class SandboxDecoder {
    sealed interface Outcome {
        class Encoded(val bytes: ByteArray, val format: OutputFormat, val width: Int, val height: Int) : Outcome {
            override fun toString() = "Encoded($format, ${width}x$height, ${bytes.size} bytes)"
        }

        data class Refused(val refusal: Refusal) : Outcome
    }

    private class Refuse(val refusal: Refusal) : RuntimeException(refusal.name)

    fun decode(input: ByteArray, maxSide: Int): Outcome {
        if (!Process.isIsolated()) return Outcome.Refused(Refusal.NotIsolated)
        if (maxSide !in 1..SandboxLimits.MAX_OUTPUT_SIDE) return Outcome.Refused(Refusal.BadRequest)
        if (input.size > SandboxLimits.MAX_INPUT_BYTES) return Outcome.Refused(Refusal.TooManyBytes)
        val format = when (val sniff = ImageSniffer.sniff(input)) {
            is Sniff.Image -> sniff.format
            Sniff.Empty -> return Outcome.Refused(Refusal.Empty)
            Sniff.Svg -> return Outcome.Refused(Refusal.Svg)
            Sniff.Unsupported -> return Outcome.Refused(Refusal.UnsupportedFormat)
            Sniff.Unknown -> return Outcome.Refused(Refusal.NotAnImage)
        }
        // Our own reading of the declared size first, so an oversized image never reaches the platform decoder.
        val (declaredWidth, declaredHeight) = ImageHeaders.size(input, format) ?: return Outcome.Refused(Refusal.Unreadable)
        if (declaredWidth !in 1..SandboxLimits.MAX_SOURCE_SIDE || declaredHeight !in 1..SandboxLimits.MAX_SOURCE_SIDE) {
            return Outcome.Refused(Refusal.TooLarge)
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width <= 0 || height <= 0 ||
                    width > SandboxLimits.MAX_SOURCE_SIDE || height > SandboxLimits.MAX_SOURCE_SIDE
                ) {
                    throw Refuse(Refusal.TooLarge)
                }
                if (info.mimeType !in DECODABLE) throw Refuse(Refusal.UnsupportedFormat)
                val scale = minOf(1.0, maxSide.toDouble() / max(width, height))
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceIn(1, maxSide),
                    (height * scale).roundToInt().coerceIn(1, maxSide),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                // A truncated or damaged image: refuse it rather than show (and cache) a half-drawn one.
                decoder.setOnPartialImageListener { false }
            }
        } catch (refused: Refuse) {
            return Outcome.Refused(refused.refusal)
        } catch (_: Exception) {
            return Outcome.Refused(Refusal.Unreadable)
        } catch (_: OutOfMemoryError) {
            return Outcome.Refused(Refusal.Unreadable)
        }
        return try {
            encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): Outcome {
        if (bitmap.width > SandboxLimits.MAX_OUTPUT_SIDE || bitmap.height > SandboxLimits.MAX_OUTPUT_SIDE) {
            return Outcome.Refused(Refusal.EncodeFailed)
        }
        val format = if (bitmap.hasAlpha()) OutputFormat.Png else OutputFormat.Jpeg
        val out = ByteArrayOutputStream(64 * 1024)
        val ok = try {
            when (format) {
                OutputFormat.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                OutputFormat.Jpeg -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok || out.size() == 0) return Outcome.Refused(Refusal.EncodeFailed)
        if (out.size() > SandboxLimits.OUTPUT_REGION_BYTES) return Outcome.Refused(Refusal.OutputTooLarge)
        return Outcome.Encoded(out.toByteArray(), format, bitmap.width, bitmap.height)
    }

    private companion object {
        const val JPEG_QUALITY = 88

        /** What the platform decoder must say it found; it sniffs on its own, so this double-checks ours. */
        val DECODABLE = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
