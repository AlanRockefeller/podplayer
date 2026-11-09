/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random

/**
 * Test images, generated in code so no binary fixtures are committed. Hostile ones are written byte by byte;
 * ordinary ones come from the platform encoder (Robolectric native graphics).
 */
internal object TestImages {
    /** A real image from the platform encoder: a gradient with a shape, so JPEG and PNG output aren't trivial. */
    fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat, alpha: Boolean = false, quality: Int = 90): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (alpha) Color.TRANSPARENT else Color.rgb(20, 60, 120))
        val paint = Paint().apply { color = Color.rgb(240, 180, 40) }
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 3f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    fun png(width: Int = 300, height: Int = 200, alpha: Boolean = false) =
        encoded(width, height, Bitmap.CompressFormat.PNG, alpha)

    fun jpeg(width: Int = 300, height: Int = 200) = encoded(width, height, Bitmap.CompressFormat.JPEG)

    fun webp(width: Int = 300, height: Int = 200) = encoded(width, height, Bitmap.CompressFormat.WEBP_LOSSLESS)

    /**
     * A grayscale PNG written by hand: any header size, with [rows] rows of zero pixels deflated into IDAT.
     * A huge header with few real rows is a decompression bomb's shape; [rows] = [height] is a complete image.
     */
    fun handMadePng(width: Int, height: Int, rows: Int = minOf(height, 4), bitDepth: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        val ihdr = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(width)
                writeInt(height)
                writeByte(bitDepth)
                writeByte(0) // grayscale
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        val rowBytes = (width.toLong() * bitDepth + 7) / 8
        val idat = ByteArrayOutputStream()
        DeflaterOutputStream(idat, Deflater(Deflater.BEST_COMPRESSION)).use { z ->
            val row = ByteArray((rowBytes + 1).toInt()) // filter byte 0, then zeros
            repeat(rows) { z.write(row) }
        }
        chunk(out, "IDAT", idat.toByteArray())
        if (rows >= height) chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        DataOutputStream(out).writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(out).writeInt(crc.value.toInt())
    }

    /** A baseline JPEG header (SOI, SOF0) claiming [width] x [height], with no image data at all. */
    fun jpegHeaderOnly(width: Int, height: Int): ByteArray = bytes(
        0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08,
        height shr 8, height and 0xFF, width shr 8, width and 0xFF,
        0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0xFF, 0xD9,
    )

    /** The classic 1 x 1 GIF, as two frames with an animation loop extension. */
    val animatedGif: ByteArray = bytes(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
        0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF,
        // NETSCAPE2.0 loop forever
        0x21, 0xFF, 0x0B, 0x4E, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2E, 0x30, 0x03, 0x01, 0x00, 0x00, 0x00,
        // frame 1: color 0 (red)
        0x21, 0xF9, 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00,
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00,
        // frame 2: color 1 (blue)
        0x21, 0xF9, 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00,
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x4C, 0x01, 0x00,
        0x3B,
    )

    val svg: ByteArray = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><circle r="40"/></svg>"""
        .toByteArray()

    /** SVG behind a byte-order mark, an XML prolog, a comment and blank lines. */
    val svgWithProlog: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "\n  <?xml version=\"1.0\"?>\n<!-- art -->\n<!DOCTYPE svg>\n<SVG viewBox=\"0 0 1 1\"></SVG>".toByteArray()

    val html: ByteArray = "<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()

    fun random(size: Int, seed: Int = 7): ByteArray = Random(seed).nextBytes(size)

    fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    val PNG_SIGNATURE = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
