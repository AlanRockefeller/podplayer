/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.TransferListener

/**
 * Turns a stream that ends before the length its server announced into a read error, so the player reconnects
 * from where it stopped instead of treating the cut as the end of the episode.
 *
 * OkHttp catches a short body over HTTP/1.1 ("unexpected end of stream"), but not over HTTP/2, and Media3's
 * `OkHttpDataSource` passes the early end on as a normal one. The extractor then believes the whole file has
 * loaded, and the player runs its own clock through the rest of the episode's duration: the position advances
 * and nothing plays. Seen on 2026-11-17 on an ad-inserted stream (Podtrac, Omny, Triton) that ended 26 minutes
 * into a 57-minute episode. As an error, the loader retries with a range request from its last position.
 *
 * Streams of unknown length can't be checked and pass through unchanged.
 */
internal class WholeBodyDataSource(private val upstream: DataSource) : DataSource {
    private var dataSpec: DataSpec? = null
    private var announced = C.LENGTH_UNSET.toLong()
    private var received = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        announced = C.LENGTH_UNSET.toLong()
        received = 0
        return upstream.open(dataSpec).also { announced = it }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (announced >= 0 && received < announced) throw cutShort()
        } else {
            received += read
        }
        return read
    }

    // The message never carries the URL: feed and media URLs can hold private tokens (CLAUDE.md).
    private fun cutShort() = HttpDataSourceException(
        "the stream ended after $received of $announced bytes",
        dataSpec ?: DataSpec(Uri.EMPTY),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        HttpDataSourceException.TYPE_READ,
    )

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        dataSpec = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = WholeBodyDataSource(upstream.createDataSource())
    }
}
