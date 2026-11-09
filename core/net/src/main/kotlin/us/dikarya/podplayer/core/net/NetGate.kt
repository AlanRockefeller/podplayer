/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

/** The outcome of [NetGate.fetch]. */
sealed interface FetchResult {
    class Success(
        val body: ByteArray,
        val contentType: String?,
        val validators: Validators,
        val redirects: RedirectChain,
    ) : FetchResult {
        override fun toString() = "Success(${body.size} bytes, $contentType, $validators, $redirects)"
    }

    /** 304: the stored copy is still current. */
    class NotModified(val redirects: RedirectChain) : FetchResult {
        override fun toString() = "NotModified($redirects)"
    }

    data class Failure(val error: NetError) : FetchResult
}

/**
 * The one HTTP client of the app (CLAUDE.md, SPEC §3). Everything that touches the network goes through
 * here: feeds, images, downloads, and ExoPlayer through [callFactory].
 *
 * What every request gets:
 * - the address filter after DNS ([GatedDns]), for IP literals ([UrlGuard]) and on the connected socket
 *   ([ConnectionGuard]), with the [AddressPolicy] exception for the owner's typed host;
 * - redirects followed by hand, at most [NetConfig.maxRedirects], each hop re-checked ([RedirectInterceptor]);
 * - connect 15 s and read 30 s timeouts, no total limit (downloads can take hours);
 * - our user agent and nothing else identifying: no cookies, no cache, no proxy (a system proxy would
 *   connect on our behalf and skip the address filter).
 *
 * Clients for different policies share one connection pool and dispatcher; pooled connections are only
 * shared between equal policies because OkHttp compares the Dns.
 */
class NetGate(private val config: NetConfig, private val resolver: Dns = Dns.SYSTEM) {
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .writeTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .cache(null)
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .build()

    private val clients = ConcurrentHashMap<AddressPolicy, OkHttpClient>()

    internal fun client(policy: AddressPolicy): OkHttpClient {
        if (clients.size > MAX_CACHED_POLICIES) clients.clear()
        return clients.getOrPut(policy) {
            base.newBuilder()
                .dns(GatedDns(resolver, policy))
                .addInterceptor(RedirectInterceptor(config.maxRedirects))
                .addInterceptor(UrlGuard(policy, config.userAgent))
                .addNetworkInterceptor(ConnectionGuard(policy))
                .build()
        }
    }

    /**
     * A gated [Call.Factory], for ExoPlayer's `OkHttpDataSource` and anything else that needs raw calls.
     * Deliberately not the [OkHttpClient] itself, so no caller can `newBuilder()` the gate away.
     * Refusals surface as [IOException]s; [NetError.from] maps them.
     */
    fun callFactory(policy: AddressPolicy = AddressPolicy.Default): Call.Factory {
        val client = client(policy)
        return Call.Factory { request -> client.newCall(request) }
    }

    /**
     * GETs a small resource (feed, OPML, chapters, image) into memory under [limit], sending [validators]
     * as a conditional GET. Never throws for network or server problems.
     */
    suspend fun fetch(
        url: HttpUrl,
        limit: BodyLimit,
        policy: AddressPolicy = AddressPolicy.Default,
        validators: Validators? = null,
    ): FetchResult {
        val request = Request.Builder().url(url).get()
            .apply { validators?.applyConditions(this) }
            .build()
        val call = client(policy).newCall(request)
        call.timeout().timeout(limit.maxDuration.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return try {
            call.awaitResponse { response -> readFetchResult(response, limit) }
        } catch (e: IOException) {
            FetchResult.Failure(NetError.from(e))
        }
    }

    private fun readFetchResult(response: Response, limit: BodyLimit): FetchResult {
        val chain = response.redirectChain
        return when {
            response.code == 304 -> FetchResult.NotModified(chain)
            !response.isSuccessful -> FetchResult.Failure(NetError.HttpStatus(response.code))
            else -> FetchResult.Success(
                body = response.body.readLimited(limit.maxBytes),
                contentType = response.header("Content-Type")?.take(200),
                validators = Validators.from(response),
                redirects = chain,
            )
        }
    }

    private companion object {
        const val MAX_IDLE_CONNECTIONS = 5
        const val KEEP_ALIVE_MINUTES = 5L
        const val MAX_CACHED_POLICIES = 64
    }
}

/** Reads at most [maxBytes] (after any transparent decompression), or throws [BodyTooLargeException]. */
internal fun ResponseBody.readLimited(maxBytes: Long): ByteArray {
    if (contentLength() > maxBytes) throw BodyTooLargeException(maxBytes)
    val source = source()
    val buffer = Buffer()
    while (buffer.size <= maxBytes) {
        if (source.read(buffer, READ_CHUNK) == -1L) break
    }
    if (buffer.size > maxBytes) throw BodyTooLargeException(maxBytes)
    return buffer.readByteArray()
}

private const val READ_CHUNK = 8L * 1024

/**
 * Runs the call on OkHttp's dispatcher and hands the open response to [block] there; the response is
 * closed afterwards. On coroutine cancellation the call is cancelled, and this waits until [block] has
 * returned, so nothing touches the caller's files or buffers after the caller has moved on.
 */
internal suspend fun <T> Call.awaitResponse(block: (Response) -> T): T {
    val done = CompletableDeferred<T>()
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                done.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    done.complete(response.use(block))
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                }
            }
        },
    )
    try {
        return done.await()
    } catch (cancelled: CancellationException) {
        cancel()
        withContext(NonCancellable) { runCatching { done.await() } }
        throw cancelled
    }
}
