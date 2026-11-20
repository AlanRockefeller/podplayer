/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache
import us.dikarya.podplayer.core.imagesandbox.client.ImageDecodeSandbox
import us.dikarya.podplayer.core.imagesandbox.client.SandboxResult
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.UrlProblem
import us.dikarya.podplayer.core.net.redact

/** The outcome of [ArtworkRepository.artwork]: a sandbox-produced file, or a reason to show a placeholder. */
sealed interface ArtworkResult {
    class Ready(val file: ArtworkFile) : ArtworkResult {
        override fun toString() = "Ready($file)"
    }

    data class Unavailable(val problem: ArtworkProblem) : ArtworkResult
}

/** Why there is no artwork. [describe] is plain English for the logs and the Developer screen. */
sealed interface ArtworkProblem {
    fun describe(): String

    data object NoImage : ArtworkProblem {
        override fun describe() = "The feed has no image."
    }

    data class BadUrl(val problem: UrlProblem) : ArtworkProblem {
        override fun describe() = "The image address can't be used ($problem)."
    }

    data class Network(val error: NetError) : ArtworkProblem {
        override fun describe() = "Couldn't fetch the image: $error."
    }

    /** The bytes are not a format we take (an SVG, a web page, ...); the sandbox wasn't needed to tell. */
    data class NotAnImage(val sniff: Sniff) : ArtworkProblem {
        override fun describe() = "The server didn't send an image we show ($sniff)."
    }

    data class Refused(val refusal: Refusal) : ArtworkProblem {
        override fun describe() = "The image sandbox refused the image ($refusal)."
    }

    data object SandboxCrashed : ArtworkProblem {
        override fun describe() = "The image crashed the image sandbox."
    }

    data object SandboxTimedOut : ArtworkProblem {
        override fun describe() = "The image sandbox took too long."
    }

    data object SandboxUnavailable : ArtworkProblem {
        override fun describe() = "The image sandbox couldn't be started."
    }

    data class BadOutput(val problem: String) : ArtworkProblem {
        override fun describe() = "The image sandbox sent back something that isn't a plain image ($problem)."
    }

    data object Storage : ArtworkProblem {
        override fun describe() = "Couldn't write the image to the cache."
    }

    /** An earlier attempt failed recently; it isn't tried again before the remembered time. */
    data class Remembered(val reason: String) : ArtworkProblem {
        override fun describe() = "Failed recently: $reason"
    }
}

/**
 * Artwork for a feed or episode image URL (SPEC §6). The only way an image gets into the cache:
 * 1. URL policy (http and https only; anything else never touches the network);
 * 2. fetched in the app process by the gated client (`NetGate`, image size limit, address filter with the feed's
 *    owner-approved host);
 * 3. magic-byte sniff, so web pages and SVGs are refused without a sandbox round trip;
 * 4. decoded and re-encoded in the isolated sandbox, the answer checked by `OutputCheck`;
 * 5. cached; Coil and Media3 read only those files.
 * Failures are remembered per URL (24 h, or 1 h for transient network trouble), so a hostile image isn't fetched
 * and decoded on every screen. One fetch per URL at a time; at most [MAX_PARALLEL_FETCHES] fetches overall.
 */
class ArtworkRepository internal constructor(
    private val gate: () -> NetGate,
    private val sandbox: ImageDecodeSandbox,
    private val cache: ImageCache,
    private val log: (String) -> Unit = {},
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ArtworkResult>>()
    private val fetches = Semaphore(MAX_PARALLEL_FETCHES)

    /** The cached file for [url], without fetching anything; null if it isn't cached (yet). */
    suspend fun cached(url: String?): ArtworkFile? {
        val checked = check(url) as? UrlCheck.Allowed ?: return null
        return withContext(Dispatchers.IO) { cache.find(ImageCache.keyFor(checked.url.toString())) }
    }

    /**
     * Artwork for [url], fetching and decoding it if needed. [approvedHost] is the owner-approved host of the
     * feed the image belongs to (SPEC §3): an image on that host may be on a private network.
     */
    suspend fun artwork(url: String?, approvedHost: String? = null): ArtworkResult {
        val checked = when (val check = check(url)) {
            null -> return ArtworkResult.Unavailable(ArtworkProblem.NoImage)
            is UrlCheck.Rejected -> return ArtworkResult.Unavailable(ArtworkProblem.BadUrl(check.problem))
            is UrlCheck.Allowed -> check.url
        }
        val key = ImageCache.keyFor(checked.toString())
        while (true) {
            val mine = CompletableDeferred<ArtworkResult>()
            val running = inFlight.putIfAbsent(key, mine)
            if (running != null) {
                try {
                    return running.await()
                } catch (cancelled: CancellationException) {
                    // The request we joined was cancelled; we weren't, so try again ourselves.
                    if (!currentCoroutineContext().isActive) throw cancelled
                    continue
                }
            }
            try {
                val result = withContext(Dispatchers.IO) { load(checked, key, approvedHost) }
                mine.complete(result)
                return result
            } catch (t: Throwable) {
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(key, mine)
            }
        }
    }

    private fun check(url: String?): UrlCheck? = url?.trim()?.takeIf { it.isNotEmpty() }?.let(UrlPolicy::checkFeedUrl)

    private suspend fun load(url: okhttp3.HttpUrl, key: String, approvedHost: String?): ArtworkResult {
        cache.find(key)?.let { return ArtworkResult.Ready(it) }
        cache.failure(key)?.let { return ArtworkResult.Unavailable(ArtworkProblem.Remembered(it.reason)) }
        val policy = approvedHost?.let { AddressPolicy.approvingHosts(it) } ?: AddressPolicy.Default
        val bytes = when (val fetched = fetches.withPermit { gate().fetch(url, BodyLimit.IMAGE, policy) }) {
            is FetchResult.Success -> fetched.body
            is FetchResult.NotModified -> return fail(url, key, ArtworkProblem.Network(NetError.HttpStatus(304)), LONG)
            is FetchResult.Failure -> return fail(url, key, ArtworkProblem.Network(fetched.error), memoryFor(fetched.error))
        }
        when (val sniff = ImageSniffer.sniff(bytes)) {
            is Sniff.Image -> Unit
            else -> return fail(url, key, ArtworkProblem.NotAnImage(sniff), LONG)
        }
        val image = when (val decoded = sandbox.decode(bytes)) {
            is SandboxResult.Decoded -> decoded.image
            is SandboxResult.Refused -> return fail(url, key, ArtworkProblem.Refused(decoded.refusal), LONG)
            SandboxResult.Crashed -> return fail(url, key, ArtworkProblem.SandboxCrashed, LONG)
            SandboxResult.TimedOut -> return fail(url, key, ArtworkProblem.SandboxTimedOut, LONG)
            SandboxResult.Unavailable -> return fail(url, key, ArtworkProblem.SandboxUnavailable, SHORT)
            is SandboxResult.BadOutput -> return fail(url, key, ArtworkProblem.BadOutput(decoded.problem), LONG)
        }
        return try {
            ArtworkResult.Ready(cache.store(key, image.format, image.bytes))
        } catch (_: IOException) {
            fail(url, key, ArtworkProblem.Storage, SHORT)
        }
    }

    private fun fail(url: okhttp3.HttpUrl, key: String, problem: ArtworkProblem, rememberMs: Long): ArtworkResult {
        log("Artwork ${redact(url)}: ${problem.describe()}")
        if (rememberMs > 0) cache.rememberFailure(key, problem.describe(), rememberMs)
        return ArtworkResult.Unavailable(problem)
    }

    /**
     * Permanent-looking failures are remembered for a day; timeouts, server errors and the like for an hour.
     * Address refusals aren't remembered: they depend on the feed's approved host, and cost nothing to repeat
     * (nothing is sent).
     */
    private fun memoryFor(error: NetError): Long = when (error) {
        is NetError.BlockedAddress, is NetError.InvalidUrl -> 0
        is NetError.HttpStatus -> if (error.code in 400..499 && error.code != 408 && error.code != 429) LONG else SHORT
        is NetError.TooLarge, is NetError.TooManyRedirects, is NetError.BadRedirect -> LONG
        else -> SHORT
    }

    private companion object {
        const val MAX_PARALLEL_FETCHES = 4
        const val LONG = 24 * 60 * 60 * 1000L
        const val SHORT = 60 * 60 * 1000L
    }
}
