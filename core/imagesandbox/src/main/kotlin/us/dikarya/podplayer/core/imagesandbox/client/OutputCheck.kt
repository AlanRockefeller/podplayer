/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.client

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.SandboxLimits

/**
 * Checks what the sandbox sent back before the app caches it (and later decodes the cached file). The sandbox
 * is treated as hostile too: an image that took over its decoder controls these bytes.
 *
 * Only the structure is parsed, never the pixels. The result must be exactly what the sandbox's encoder
 * writes: a non-interlaced or interlaced PNG (IHDR, optional PLTE and tRNS, consecutive IDATs, IEND, every CRC
 * correct) or a baseline JPEG (DQT, DHT, DRI, one SOF0, one SOS, entropy data, EOI), at most [maxSide] pixels a
 * side. Everything a plain image doesn't need (text, colour profiles, EXIF, comments) is removed, so the app's
 * decoder never parses those parts. Anything else is refused.
 */
object OutputCheck {
    sealed interface Result {
        /** [bytes] is the cleaned image: what gets cached. */
        class Valid(val format: OutputFormat, val width: Int, val height: Int, val bytes: ByteArray) : Result {
            override fun toString() = "Valid($format, ${width}x$height, ${bytes.size} bytes)"
        }

        data class Invalid(val problem: String) : Result
    }

    fun inspect(bytes: ByteArray, maxSide: Int = SandboxLimits.MAX_OUTPUT_SIDE): Result {
        if (bytes.size > SandboxLimits.OUTPUT_REGION_BYTES) return Result.Invalid("too many bytes")
        return when {
            bytes.size >= 8 && (0 until 8).all { (bytes[it].toInt() and 0xFF) == PNG_SIGNATURE[it] } ->
                png(bytes, maxSide)
            bytes.size >= 2 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 -> jpeg(bytes, maxSide)
            else -> Result.Invalid("neither PNG nor JPEG")
        }
    }

    private fun png(b: ByteArray, maxSide: Int): Result {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var pos = 8
        var width = 0
        var height = 0
        var colorType = -1
        var chunks = 0
        var sawPalette = false
        var idatState = 0 // 0 none yet, 1 in the IDAT run, 2 after it
        while (true) {
            if (b.size - pos < 12) return Result.Invalid("PNG truncated")
            val length = i32(b, pos)
            if (length < 0 || length > b.size - pos - 12) return Result.Invalid("PNG chunk length out of range")
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) return Result.Invalid("PNG chunk type not letters")
            val crc = CRC32().apply { update(b, pos + 4, 4 + length) }.value
            if (crc != (i32(b, pos + 8 + length).toLong() and 0xFFFFFFFFL)) return Result.Invalid("PNG CRC mismatch")
            val dataStart = pos + 8
            val end = pos + 12 + length
            if (chunks == 0 && type != "IHDR") return Result.Invalid("PNG doesn't start with IHDR")
            if (idatState == 1 && type != "IDAT") idatState = 2
            var keep = true
            when (type) {
                "IHDR" -> {
                    if (chunks != 0 || length != 13) return Result.Invalid("PNG IHDR misplaced or wrong size")
                    width = i32(b, dataStart)
                    height = i32(b, dataStart + 4)
                    val depth = u8(b, dataStart + 8)
                    colorType = u8(b, dataStart + 9)
                    if (width !in 1..maxSide || height !in 1..maxSide) return Result.Invalid("PNG size out of range")
                    if (depth !in (PNG_DEPTHS[colorType] ?: emptySet())) return Result.Invalid("PNG depth/colour type")
                    if (u8(b, dataStart + 10) != 0 || u8(b, dataStart + 11) != 0 || u8(b, dataStart + 12) > 1) {
                        return Result.Invalid("PNG compression, filter or interlace method")
                    }
                }
                "PLTE" -> {
                    if (sawPalette || idatState != 0 || length == 0 || length % 3 != 0 || length > 768) {
                        return Result.Invalid("PNG palette")
                    }
                    sawPalette = true
                }
                "tRNS" -> if (idatState != 0 || length > 256) return Result.Invalid("PNG transparency")
                "IDAT" -> {
                    if (idatState == 2) return Result.Invalid("PNG IDAT chunks not consecutive")
                    if (colorType == 3 && !sawPalette) return Result.Invalid("PNG palette missing")
                    idatState = 1
                }
                "IEND" -> {
                    if (length != 0 || idatState == 0) return Result.Invalid("PNG IEND misplaced")
                    if (end != b.size) return Result.Invalid("bytes after PNG IEND")
                    out.write(b, pos, end - pos)
                    return Result.Valid(OutputFormat.Png, width, height, out.toByteArray())
                }
                else -> {
                    if (type[0] in 'A'..'Z') return Result.Invalid("unknown critical PNG chunk")
                    keep = false // ancillary: text, colour profile, EXIF, time, ...
                }
            }
            if (keep) out.write(b, pos, end - pos)
            chunks++
            pos = end
        }
    }

    private fun jpeg(b: ByteArray, maxSide: Int): Result {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var pos = 2
        var width = 0
        var height = 0
        while (true) {
            if (b.size - pos < 4) return Result.Invalid("JPEG truncated")
            if (u8(b, pos) != 0xFF) return Result.Invalid("JPEG segment doesn't start with a marker")
            val marker = u8(b, pos + 1)
            val length = (u8(b, pos + 2) shl 8) or u8(b, pos + 3)
            if (length < 2 || pos + 2 + length > b.size) return Result.Invalid("JPEG segment length out of range")
            val end = pos + 2 + length
            when (marker) {
                0xC0 -> {
                    if (width != 0) return Result.Invalid("JPEG has two frames")
                    val components = if (length >= 8) u8(b, pos + 9) else 0
                    if (u8(b, pos + 4) != 8 || (components != 1 && components != 3) || length != 8 + 3 * components) {
                        return Result.Invalid("JPEG frame header")
                    }
                    height = (u8(b, pos + 5) shl 8) or u8(b, pos + 6)
                    width = (u8(b, pos + 7) shl 8) or u8(b, pos + 8)
                    if (width !in 1..maxSide || height !in 1..maxSide) return Result.Invalid("JPEG size out of range")
                    out.write(b, pos, end - pos)
                }
                0xC4, 0xDB, 0xDD -> out.write(b, pos, end - pos) // Huffman tables, quantisation tables, restarts
                in 0xE0..0xEF, 0xFE -> Unit // APPn (JFIF, EXIF, ICC, XMP) and comments: dropped
                0xDA -> {
                    if (width == 0) return Result.Invalid("JPEG scan before frame")
                    return scan(b, end)?.let { Result.Invalid(it) } ?: run {
                        out.write(b, pos, b.size - pos)
                        Result.Valid(OutputFormat.Jpeg, width, height, out.toByteArray())
                    }
                }
                else -> return Result.Invalid("JPEG marker not allowed")
            }
            pos = end
        }
    }

    /** Entropy-coded data from [start] must hold only stuffed bytes and restart markers, then end with EOI. */
    private fun scan(b: ByteArray, start: Int): String? {
        val last = b.size - 2
        if (last < start || u8(b, last) != 0xFF || u8(b, last + 1) != 0xD9) return "JPEG doesn't end with EOI"
        var i = start
        while (i < last) {
            if (u8(b, i) == 0xFF) {
                val next = if (i + 1 < last) u8(b, i + 1) else return "JPEG marker cut off"
                if (next != 0x00 && next !in 0xD0..0xD7) return "JPEG marker inside the scan"
                i += 2
            } else {
                i++
            }
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun i32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Bit depths allowed for each PNG colour type. */
    private val PNG_DEPTHS = mapOf(
        0 to setOf(1, 2, 4, 8, 16),
        2 to setOf(8, 16),
        3 to setOf(1, 2, 4, 8),
        4 to setOf(8, 16),
        6 to setOf(8, 16),
    )
}
