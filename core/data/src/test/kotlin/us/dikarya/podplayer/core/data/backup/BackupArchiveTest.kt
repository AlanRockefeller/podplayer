/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CLAUDE.md: every parser has byte, item-count *and time* limits, and a backup is any file the owner picks. */
class BackupArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A zip whose database entry is big enough to need many reads. */
    private val archive: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE).apply { method = ZipEntry.STORED; size = 1L shl 20; crc = crcOfZeros(1 shl 20) })
            zip.write(ByteArray(1 shl 20))
            zip.closeEntry()
        }
        out.toByteArray()
    }

    private fun crcOfZeros(n: Int): Long = java.util.zip.CRC32().apply { update(ByteArray(n)) }.value

    @Test
    fun aStreamThatTricklesIsStoppedAtTheTimeLimit() {
        // CodeRabbit review (PR 3): a slow provider could stay under every byte and entry limit and keep the read
        // (and the backup lock) going for ever. Here every read takes "a second" of a test clock.
        var clock = 0L
        val trickle = object : InputStream() {
            val bytes = archive.inputStream()

            override fun read(): Int = bytes.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                clock += 1_000
                return bytes.read(b, off, minOf(len, 16))
            }
        }
        val result = BackupArchive.read(trickle, File(tmp.root, "out.db"), BackupLimits(maxReadMs = 30_000)) { clock }
        assertEquals(BackupArchive.ReadResult.Refused(RestoreProblem.Unreadable), result)
    }

    @Test(timeout = 20_000)
    fun aStreamThatNeverAnswersIsClosedAtTheTimeLimit() {
        val closed = CountDownLatch(1)
        val stalled = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                closed.await()
                throw IOException("closed")
            }

            override fun close() = closed.countDown()
        }
        val result = BackupArchive.read(stalled, File(tmp.root, "out.db"), BackupLimits(maxReadMs = 300))
        assertEquals(BackupArchive.ReadResult.Refused(RestoreProblem.Unreadable), result)
    }
}
