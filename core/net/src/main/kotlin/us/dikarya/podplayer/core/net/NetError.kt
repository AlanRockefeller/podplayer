/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.io.IOException
import java.io.InterruptedIOException

/**
 * Why a request failed, as a value the UI can turn into a plain message (strings live in `:app`).
 * Nothing in here holds a URL with its query, fragment or userinfo.
 */
sealed interface NetError {
    data class InvalidUrl(val problem: UrlProblem) : NetError

    /** The address filter refused this host. */
    data class BlockedAddress(val host: String, val reason: BlockReason?) : NetError

    data class TooManyRedirects(val limit: Int) : NetError

    /** A redirect without a usable Location, or to a scheme other than http/https. [reason] is our own text. */
    data class BadRedirect(val reason: String) : NetError

    data class HttpStatus(val code: Int) : NetError

    data class TooLarge(val limitBytes: Long) : NetError

    /** Connect or read timeout, or the total time limit of a [BodyLimit]. */
    data object TimedOut : NetError

    /** A download received too little data for too long ([us.dikarya.podplayer.core.net.download.StallPolicy]). */
    data object Stalled : NetError

    /** Any other network failure. [detail] is scrubbed of URLs. */
    data class Network(val detail: String) : NetError

    /** Writing to local storage failed, for example because the disk is full. [detail] is scrubbed. */
    data class Storage(val detail: String) : NetError

    companion object {
        /** Maps a failure from the gated client to a [NetError]. */
        fun from(error: IOException): NetError {
            for (cause in error.causes()) {
                when (cause) {
                    is BlockedAddressException -> return BlockedAddress(cause.host, cause.reason)
                    is DisallowedUrlException -> return InvalidUrl(cause.problem)
                    is TooManyRedirectsException -> return TooManyRedirects(cause.limit)
                    is BadRedirectException -> return BadRedirect(cause.message.orEmpty())
                    is BodyTooLargeException -> return TooLarge(cause.limitBytes)
                }
            }
            if (error is InterruptedIOException) return TimedOut
            return Network(describe(error))
        }

        internal fun describe(error: Throwable): String =
            scrubUrls("${error.javaClass.simpleName}: ${error.message.orEmpty()}").take(300)

        private fun Throwable.causes(): Sequence<Throwable> =
            generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(10)
                .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
    }
}
