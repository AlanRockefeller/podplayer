/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import java.nio.ByteBuffer

/** Image formats the sandbox decodes. Everything else is refused, even formats Android could decode. */
enum class SourceFormat(val mimeType: String) {
    Png("image/png"),
    Jpeg("image/jpeg"),
    Gif("image/gif"),
    WebP("image/webp"),
}

/** Formats the sandbox writes and the cache holds: JPEG for opaque images, PNG when there is alpha (SPEC §6). */
enum class OutputFormat(val mimeType: String, val extension: String) {
    Png("image/png", "png"),
    Jpeg("image/jpeg", "jpg"),
}

/** What a byte stream looks like. Labels (Content-Type, file names) are never trusted; only the bytes are. */
sealed interface Sniff {
    data class Image(val format: SourceFormat) : Sniff

    /** SPEC §6: SVG is refused (it is a document format with scripts and external references). */
    data object Svg : Sniff

    /** An image or compressed format we don't take: HEIF, AVIF, BMP, ICO, TIFF, gzip (svgz). */
    data object Unsupported : Sniff

    /** Not an image at all, for example a web page or an error message. */
    data object Unknown : Sniff

    data object Empty : Sniff
}

/**
 * Magic-byte sniffing. It only compares a few leading bytes and never parses the image, so it is safe on
 * untrusted input in any process; both the app (to skip a pointless sandbox call) and the sandbox use it.
 */
object ImageSniffer {
    fun sniff(bytes: ByteArray): Sniff = sniff(ByteBuffer.wrap(bytes))

    fun sniff(buffer: ByteBuffer): Sniff {
        val b = buffer.duplicate()
        val n = b.remaining()
        if (n == 0) return Sniff.Empty
        fun at(i: Int): Int = if (i < n) b.get(b.position() + i).toInt() and 0xFF else -1
        fun ascii(offset: Int, text: String) = text.indices.all { at(offset + it) == text[it].code }
        return when {
            n >= 8 && PNG.indices.all { at(it) == PNG[it] } -> Sniff.Image(SourceFormat.Png)
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Sniff.Image(SourceFormat.Jpeg)
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> Sniff.Image(SourceFormat.Gif)
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Sniff.Image(SourceFormat.WebP)
            ascii(4, "ftyp") -> Sniff.Unsupported
            ascii(0, "BM") -> Sniff.Unsupported
            at(0) == 0 && at(1) == 0 && (at(2) == 1 || at(2) == 2) && at(3) == 0 -> Sniff.Unsupported
            ascii(0, "II*") && at(3) == 0 || ascii(0, "MM") && at(2) == 0 && at(3) == 0x2A -> Sniff.Unsupported
            at(0) == 0x1F && at(1) == 0x8B -> Sniff.Unsupported
            looksLikeSvg(b, n) -> Sniff.Svg
            else -> Sniff.Unknown
        }
    }

    /** Markup (after an optional BOM and whitespace) whose first [SVG_WINDOW] bytes mention an `<svg` element. */
    private fun looksLikeSvg(b: ByteBuffer, n: Int): Boolean {
        val window = minOf(n, SVG_WINDOW)
        val head = ByteArray(window).also { b.duplicate().get(it) }
        val text = String(head, Charsets.ISO_8859_1).removePrefix(UTF8_BOM).trimStart()
        return text.startsWith("<") && text.contains("<svg", ignoreCase = true)
    }

    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val SVG_WINDOW = 2048

    /** The UTF-8 byte-order mark as Latin-1 characters (EF BB BF). */
    private val UTF8_BOM = String(charArrayOf(Char(0xEF), Char(0xBB), Char(0xBF)))
}

/**
 * The size an image's header declares, read from a few fixed fields (PNG IHDR, the JPEG frame header, the GIF
 * logical screen, the WebP VP8/VP8L/VP8X header) without decoding anything. The sandbox refuses oversized
 * images with this before the platform decoder sees them; the decoder's own header callback checks again.
 * Null when the header is cut off or makes no sense.
 */
object ImageHeaders {
    fun size(bytes: ByteArray, format: SourceFormat): Pair<Int, Int>? = try {
        when (format) {
            SourceFormat.Png -> png(bytes)
            SourceFormat.Jpeg -> jpeg(bytes)
            SourceFormat.Gif -> if (bytes.size >= 10) le16(bytes, 6) to le16(bytes, 8) else null
            SourceFormat.WebP -> webp(bytes)
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun png(b: ByteArray): Pair<Int, Int>? {
        // Signature, then the IHDR chunk: length 13, type, width, height.
        if (b.size < 24 || be32(b, 8) != 13 || String(b, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
        return be32(b, 16) to be32(b, 20)
    }

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        var pos = 2
        while (pos + 4 <= b.size) {
            if (u8(b, pos) != 0xFF) return null
            val marker = u8(b, pos + 1)
            when {
                marker == 0xFF -> pos++ // fill byte
                marker == 0x01 || marker in 0xD0..0xD7 -> pos += 2 // markers without a length
                marker == 0xD9 || marker == 0xDA -> return null // end of image, or a scan before any frame
                else -> {
                    val length = be16(b, pos + 2)
                    if (length < 2) return null
                    val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                    if (isFrame) return if (length >= 7) be16(b, pos + 7) to be16(b, pos + 5) else null
                    pos += 2 + length
                }
            }
        }
        return null
    }

    private fun webp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        return when (String(b, 12, 4, Charsets.ISO_8859_1)) {
            "VP8X" -> (le24(b, 24) + 1) to (le24(b, 27) + 1)
            "VP8L" -> {
                if (u8(b, 20) != 0x2F) return null
                val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
            }
            "VP8 " -> {
                if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null
                (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
            }
            else -> null
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
}

/** Limits shared by the app and the sandbox (SPEC §6). */
object SandboxLimits {
    /** SPEC §3 and §6: images up to 10 MB. Equal to `BodyLimit.IMAGE`. */
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024

    /** Larger than this on either side is refused from the header, before any pixel is decoded. */
    const val MAX_SOURCE_SIDE: Int = 8_000

    /** Decoded and re-encoded at most this large on the longer side. */
    const val MAX_OUTPUT_SIDE: Int = 1_024

    /**
     * The output region the app hands the sandbox. A 1,024 px PNG of pure noise is about 4.2 MB; the sandbox
     * can never send more than this.
     */
    const val OUTPUT_REGION_BYTES: Int = 6 * 1024 * 1024

    /** The sandbox kills its own process when one decode takes longer than this. The app waits 5 s. */
    const val DECODE_TIME_LIMIT_MS: Long = 4_000
}

/** Why the sandbox refused an image. Sent across the process boundary as the (negative) [code]. */
enum class Refusal(val code: Int) {
    Empty(-1),

    /** Over [SandboxLimits.MAX_INPUT_BYTES]. */
    TooManyBytes(-2),
    Svg(-3),
    UnsupportedFormat(-4),
    NotAnImage(-5),

    /** Over [SandboxLimits.MAX_SOURCE_SIDE] on a side, or no size at all. */
    TooLarge(-6),

    /** Truncated, damaged, or otherwise not decodable. */
    Unreadable(-7),
    EncodeFailed(-8),

    /** The re-encoded image didn't fit in the output region. */
    OutputTooLarge(-9),

    /** The decoder was asked to run outside an isolated process, and refused. */
    NotIsolated(-10),
    BadRequest(-11),
    ;

    companion object {
        fun fromCode(code: Int): Refusal? = entries.firstOrNull { it.code == code }
    }
}
