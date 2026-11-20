/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A stream that ends before its announced length is an error to retry, not the end of the episode. */
@RunWith(AndroidJUnit4::class)
class WholeBodyDataSourceTest {
    private val spec = DataSpec(Uri.parse("https://media.example.com/a.mp3?token=secret"))

    @Test
    fun aBodyThatStopsShortOfItsAnnouncedLengthFailsTheRead() {
        val source = WholeBodyDataSource(Body(ByteArray(600), announced = 1_000))
        assertEquals(1_000, source.open(spec))
        val error = assertThrows(HttpDataSourceException::class.java) { drain(source) }
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, error.reason)
        assertEquals(HttpDataSourceException.TYPE_READ, error.type)
        assertEquals("the stream ended after 600 of 1000 bytes", error.message)
        assertFalse("never the URL, which may carry a token", "secret" in error.toString())
    }

    @Test
    fun aWholeBodyEndsNormally() {
        val source = WholeBodyDataSource(Body(ByteArray(1_000), announced = 1_000))
        source.open(spec)
        assertEquals(1_000, drain(source))
    }

    @Test
    fun aBodyOfUnknownLengthEndsWhereverItEnds() {
        val source = WholeBodyDataSource(Body(ByteArray(600), announced = C.LENGTH_UNSET.toLong()))
        source.open(spec)
        assertEquals(600, drain(source))
    }

    @Test
    fun eachOpenCountsAfresh() {
        val body = Body(ByteArray(600), announced = 1_000)
        val source = WholeBodyDataSource(body)
        source.open(spec)
        assertThrows(HttpDataSourceException::class.java) { drain(source) }
        source.close()
        body.announced = 600
        source.open(spec.subrange(400))
        assertEquals(600, drain(source))
    }

    @Test
    fun anEmptyReadIsNotAnEnd() {
        val source = WholeBodyDataSource(Body(ByteArray(10), announced = 1_000))
        source.open(spec)
        assertEquals(0, source.read(ByteArray(8), 0, 0))
    }

    @Test
    fun theFactoryWrapsEverySource() {
        val body = Body(ByteArray(600), announced = 1_000)
        val source = WholeBodyDataSource.Factory { body }.createDataSource()
        assertTrue(source is WholeBodyDataSource)
        source.open(spec)
        assertThrows(HttpDataSourceException::class.java) { drain(source) }
    }

    private fun drain(source: DataSource): Int {
        val buffer = ByteArray(128)
        var total = 0
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return total
            total += read
        }
    }

    /** Serves [data] from the start on every open and announces [announced] bytes, like a server's Content-Length. */
    private class Body(private val data: ByteArray, var announced: Long) : DataSource {
        private var position = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            position = 0
            return announced
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }
}
