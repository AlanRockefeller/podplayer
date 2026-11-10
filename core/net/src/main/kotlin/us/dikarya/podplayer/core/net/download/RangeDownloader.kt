/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net.download

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyTooLargeException
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.core.net.NetLimits
import us.dikarya.podplayer.core.net.RedirectChain
import us.dikarya.podplayer.core.net.Validators
import us.dikarya.podplayer.core.net.awaitResponse
import us.dikarya.podplayer.core.net.redact
import us.dikarya.podplayer.core.net.redirectChain

/**
 * A download counts as stalled when fewer than [minBytes] arrive in any [window]. This catches both a
 * silent server and a slow drip that never trips the socket read timeout (LESSONS "Downloads").
 */
class StallPolicy(val window: Duration = 30.seconds, val minBytes: Long = 1024)

class DownloadRequest(
    val url: HttpUrl,
    val policy: AddressPolicy = AddressPolicy.Default,
    /** Stored from the previous attempt; needed to resume a partial file. */
    val validators: Validators? = null,
    val maxBytes: Long = NetLimits.MEDIA_MAX_BYTES,
) {
    override fun toString() = "DownloadRequest(${redact(url)}, maxBytes=$maxBytes)"
}

/** Called on the download's reading thread between reads; keep it quick. [totalBytes] is null while the size is unknown. */
fun interface DownloadProgressListener {
    fun onProgress(bytesOnDisk: Long, totalBytes: Long?)
}

/** Why a partial file was thrown away and the download started over (for the download log). */
enum class RestartReason {
    /** There was a partial file but no strong ETag or Last-Modified to resume it safely. */
    NoValidator,

    /** The server answered a Range request with the whole file. */
    RangeIgnored,

    /** The server sent a different file (its validator changed), so the old part was useless. */
    FileChanged,

    /** The 206 response covered the wrong bytes. */
    BadContentRange,

    /** The server answered 416 although the file isn't complete. */
    RangeNotSatisfiable,
}

sealed interface DownloadResult {
    /**
     * The transfer ended cleanly. That is all this says: the caller still probes the file with Media3
     * (SPEC §9) and never compares against the length the feed declared.
     */
    class Complete(
        val bytes: Long,
        val contentType: String?,
        val validators: Validators,
        /** Where this transfer started; 0 for a fresh or restarted download. */
        val resumedFrom: Long,
        val restart: RestartReason?,
        val redirects: RedirectChain,
    ) : DownloadResult {
        override fun toString() = "Complete($bytes bytes, from $resumedFrom, restart=$restart, $redirects)"
    }

    /** The bytes already on disk are kept; resume later with [validators]. */
    class Failed(
        val error: NetError,
        val bytesOnDisk: Long,
        val validators: Validators,
        val restart: RestartReason?,
    ) : DownloadResult {
        override fun toString() = "Failed($error, $bytesOnDisk bytes on disk, restart=$restart)"
    }
}

/**
 * Downloads episode media through the gated client (SPEC §9): resumes with `Range` + `If-Range` when it
 * safely can, restarts when the server ignores Range or the file changed, stops on stalls, reports
 * progress, and on cancellation stops writing before it returns. Pure Kotlin: no Android imports.
 */
class RangeDownloader(
    private val gate: NetGate,
    private val stallPolicy: StallPolicy = StallPolicy(),
    private val progressInterval: Duration = 500.milliseconds,
) {
    suspend fun download(
        request: DownloadRequest,
        sink: DownloadSink,
        onProgress: DownloadProgressListener = DownloadProgressListener { _, _ -> },
    ): DownloadResult {
        val fallbackValidators = request.validators ?: Validators.None
        val existing = try {
            sink.length()
        } catch (e: IOException) {
            return DownloadResult.Failed(NetError.Storage(NetError.describe(e)), 0, fallbackValidators, null)
        }
        val ifRange = request.validators?.ifRangeValue
        var restart: RestartReason? = null
        var offset = existing
        if (existing > 0 && ifRange == null) {
            restart = RestartReason.NoValidator
            offset = 0
            truncate(sink)?.let { return it.failed(sink, fallbackValidators, restart) }
        }
        var resumedFrom = offset
        var restarted = false
        var continuations = 0
        var outcome = attempt(request, sink, offset, ifRange, restart, resumedFrom, onProgress)
        while (true) {
            outcome = when (val current = outcome) {
                is Attempt.Done -> return current.result
                is Attempt.Retry -> {
                    // One restart from zero per download; a second unusable answer is a failure.
                    if (restarted) {
                        return NetError.Network("the server sent an unusable partial response")
                            .failed(sink, fallbackValidators, restart)
                    }
                    restarted = true
                    restart = current.reason
                    truncate(sink)?.let { return it.failed(sink, fallbackValidators, restart) }
                    resumedFrom = 0
                    attempt(request, sink, 0, null, restart, resumedFrom, onProgress)
                }
                is Attempt.Continue -> {
                    // The server sent less than asked for and said so: ask for the rest, within reason.
                    if (++continuations > MAX_CONTINUATIONS) {
                        return NetError.Network("the server split the file into more than $MAX_CONTINUATIONS pieces")
                            .failed(sink, current.validators, restart)
                    }
                    attempt(request, sink, current.offset, current.ifRange, restart, resumedFrom, onProgress)
                }
            }
        }
    }

    private sealed interface Attempt {
        class Done(val result: DownloadResult) : Attempt

        /** Throw the partial file away and start over, for [reason]. */
        class Retry(val reason: RestartReason) : Attempt

        /** The response ended, as announced, before the end of the file: fetch on from [offset]. */
        class Continue(val offset: Long, val ifRange: String, val validators: Validators) : Attempt
    }

    /** One request. [offset] > 0 asks for the rest of the file; [resumedFrom] is where the whole download started. */
    private suspend fun attempt(
        request: DownloadRequest,
        sink: DownloadSink,
        offset: Long,
        ifRange: String?,
        restart: RestartReason?,
        resumedFrom: Long,
        onProgress: DownloadProgressListener,
    ): Attempt {
        val httpRequest = Request.Builder().url(request.url).get()
            // Never let OkHttp gunzip: byte offsets must match the file on the server.
            .header("Accept-Encoding", "identity")
            .apply { if (offset > 0 && ifRange != null) header("Range", "bytes=$offset-").header("If-Range", ifRange) }
            .build()
        val call = gate.client(request.policy).newCall(httpRequest)
        val transfer = Transfer(request, sink, offset, ifRange, restart, resumedFrom, onProgress)
        return coroutineScope {
            val watchdog = launch { watchForStall(call, transfer) }
            try {
                call.awaitResponse(gate.transferDispatcher) { response -> transfer.handle(response) }
            } catch (e: IOException) {
                val error = when {
                    transfer.stalled.get() -> NetError.Stalled
                    e is SinkFailure -> NetError.Storage(NetError.describe(e.cause ?: e))
                    else -> NetError.from(e)
                }
                val validators = transfer.seenValidators.get() ?: request.validators ?: Validators.None
                Attempt.Done(error.failed(sink, validators, transfer.restart))
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun watchForStall(call: Call, transfer: Transfer) {
        var seen = 0L
        while (true) {
            delay(stallPolicy.window)
            val now = transfer.received.get()
            if (now - seen < stallPolicy.minBytes) {
                transfer.stalled.set(true)
                call.cancel()
                return
            }
            seen = now
        }
    }

    /** State of one attempt, touched by the reading thread and read by the watchdog. */
    private inner class Transfer(
        val request: DownloadRequest,
        val sink: DownloadSink,
        var offset: Long,
        /** The `If-Range` value sent with this attempt, if any. */
        val ifRange: String?,
        var restart: RestartReason?,
        var resumedFrom: Long,
        val onProgress: DownloadProgressListener,
    ) {
        val received = AtomicLong(0)
        val stalled = AtomicBoolean(false)
        val seenValidators = AtomicReference<Validators?>(null)

        fun handle(response: Response): Attempt {
            val validators = Validators.from(response)
            seenValidators.set(validators)
            return when (response.code) {
                206 -> handlePartial(response, validators)
                200 -> handleFull(response, validators)
                416 -> handleUnsatisfiable(response, validators)
                else -> Attempt.Done(NetError.HttpStatus(response.code).failed(sink, validators, restart))
            }
        }

        private fun handlePartial(response: Response, validators: Validators): Attempt {
            val range = ContentRange.parse(response.header("Content-Range"))
            val multipart = response.header("Content-Type")?.startsWith("multipart/", ignoreCase = true) == true
            if (range?.first != offset || multipart) {
                if (offset > 0) return Attempt.Retry(RestartReason.BadContentRange)
                return Attempt.Done(NetError.Network("unexpected partial response").failed(sink, validators, restart))
            }
            val total = range.total ?: response.body.contentLength().takeIf { it >= 0 }?.let { it + offset }
            return writeBody(response, validators, total, announcedEnd = range.last?.plus(1))
        }

        private fun handleFull(response: Response, validators: Validators): Attempt {
            val declared = response.body.contentLength().takeIf { it >= 0 }
            if (declared != null && declared > request.maxBytes) throw BodyTooLargeException(request.maxBytes)
            if (offset > 0) {
                restart = if (changed(request.validators, validators)) RestartReason.FileChanged else RestartReason.RangeIgnored
                sinkOp { sink.truncate() }
                offset = 0
                resumedFrom = 0
            }
            return writeBody(response, validators, declared, announcedEnd = declared)
        }

        private fun handleUnsatisfiable(response: Response, validators: Validators): Attempt {
            val total = ContentRange.parse(response.header("Content-Range"))?.total
            if (offset > 0 && total == offset) {
                return Attempt.Done(complete(response, validators, bytes = offset))
            }
            if (offset > 0) return Attempt.Retry(RestartReason.RangeNotSatisfiable)
            return Attempt.Done(NetError.HttpStatus(416).failed(sink, validators, restart))
        }

        /**
         * Streams the body to the sink. [total] is the whole file's size when known; [announcedEnd] is where
         * the server said this response's bytes end (from Content-Range, or Content-Length for a full response).
         */
        private fun writeBody(response: Response, validators: Validators, total: Long?, announcedEnd: Long?): Attempt {
            if (total != null && total > request.maxBytes) throw BodyTooLargeException(request.maxBytes)
            val source = response.body.source()
            val buffer = ByteArray(BUFFER_SIZE)
            var onDisk = offset
            var reported = -1L
            var lastReport = TimeSource.Monotonic.markNow()
            var failure: Throwable? = null
            try {
                while (true) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (onDisk + count > request.maxBytes) throw BodyTooLargeException(request.maxBytes)
                    sinkOp { sink.write(buffer, 0, count) }
                    onDisk += count
                    received.addAndGet(count.toLong())
                    if (lastReport.elapsedNow() >= progressInterval) {
                        onProgress.onProgress(onDisk, total)
                        reported = onDisk
                        lastReport = TimeSource.Monotonic.markNow()
                    }
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                // Flush what arrived so a later attempt can resume from it; keep the first error.
                try {
                    sink.close()
                } catch (e: IOException) {
                    if (failure == null) throw SinkFailure(e) else failure.addSuppressed(e)
                }
            }
            if (total != null && onDisk != total) {
                if (reported != onDisk) onProgress.onProgress(onDisk, total)
                return shortBody(onDisk, total, announcedEnd, validators)
            }
            if (reported != onDisk) onProgress.onProgress(onDisk, total ?: onDisk)
            return Attempt.Done(complete(response, validators, onDisk))
        }

        /**
         * The body ended cleanly before the end of the file. A server may answer a Range request with a
         * smaller piece than asked for (RFC 9110 §15.3.7) and say so in Content-Range: then the download
         * carries on from where the piece ended, with the validator the server has already accepted. Anything
         * else (bytes missing from what was announced, no validator, pieces too small to be a server capping
         * ranges in good faith) is a failure that the next attempt resumes from.
         */
        private fun shortBody(onDisk: Long, total: Long, announcedEnd: Long?, validators: Validators): Attempt {
            val failure = when {
                onDisk != announcedEnd -> "the transfer ended after $onDisk of $total bytes"
                onDisk - offset < MIN_CONTINUATION_BYTES ->
                    "the server sent only ${onDisk - offset} bytes of the remaining ${total - offset}"
                else -> null
            }
            if (failure != null) return Attempt.Done(NetError.Network(failure).failed(sink, validators, restart))
            val next = ifRange ?: validators.ifRangeValue
                ?: return Attempt.Done(
                    NetError.Network("the server sent $onDisk of $total bytes and no validator to fetch the rest with")
                        .failed(sink, validators, restart),
                )
            return Attempt.Continue(onDisk, next, validators)
        }

        private fun complete(response: Response, validators: Validators, bytes: Long) = DownloadResult.Complete(
            bytes = bytes,
            contentType = response.header("Content-Type")?.take(200),
            validators = validators,
            resumedFrom = resumedFrom,
            restart = restart,
            redirects = response.redirectChain,
        )
    }

    private fun changed(old: Validators?, new: Validators): Boolean {
        if (old == null) return false
        return (old.etag != null && new.etag != null && old.etag != new.etag) ||
            (old.lastModified != null && new.lastModified != null && old.lastModified != new.lastModified)
    }

    /** Truncates the sink; returns the error instead of throwing. */
    private fun truncate(sink: DownloadSink): NetError? = try {
        sink.truncate()
        null
    } catch (e: IOException) {
        NetError.Storage(NetError.describe(e))
    }

    private fun NetError.failed(sink: DownloadSink, validators: Validators, restart: RestartReason?) =
        DownloadResult.Failed(this, safeLength(sink), validators, restart)

    private fun safeLength(sink: DownloadSink): Long = try {
        sink.length()
    } catch (_: IOException) {
        0
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** The most pieces a server may split one download into before it counts as misbehaving. */
        const val MAX_CONTINUATIONS = 4096

        /** A piece smaller than this that doesn't end the file is not a server capping ranges in good faith. */
        const val MIN_CONTINUATION_BYTES = 64L * 1024
    }
}

/** A storage failure, kept apart from network failures so the log can say "disk full", not "network". */
private class SinkFailure(cause: IOException) : IOException(cause)

private inline fun sinkOp(block: () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        throw SinkFailure(e)
    }
}
