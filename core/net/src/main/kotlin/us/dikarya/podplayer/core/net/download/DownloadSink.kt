/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where a download's bytes go. The engine only appends, truncates to zero, and closes; any of these may
 * throw [IOException] (disk full, file removed), which the engine reports as a storage error.
 */
interface DownloadSink {
    /** Bytes already there from an earlier attempt: the resume point. */
    fun length(): Long

    /** Discards everything written so far. */
    fun truncate()

    fun write(bytes: ByteArray, offset: Int, count: Int)

    /** Flushes to durable storage and releases the file. The sink may be used again afterwards. */
    fun close()
}

/** A [DownloadSink] backed by a file. The caller chooses the (generated, never server-supplied) name. */
class FileDownloadSink(private val file: File) : DownloadSink {
    private var stream: FileOutputStream? = null

    override fun length(): Long = file.length()

    override fun truncate() {
        close()
        FileOutputStream(file, false).close()
    }

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        val out = stream ?: FileOutputStream(file, true).also { stream = it }
        out.write(bytes, offset, count)
    }

    override fun close() {
        val out = stream ?: return
        stream = null
        out.use {
            it.flush()
            it.fd.sync()
        }
    }
}
