/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import android.os.Looper
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okio.Buffer
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowProcess
import us.dikarya.podplayer.core.imagesandbox.client.SandboxClient
import us.dikarya.podplayer.core.imagesandbox.client.SandboxConnector
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox
import us.dikarya.podplayer.core.imagesandbox.sandbox.SandboxBinder
import us.dikarya.podplayer.core.imagesandbox.sandbox.SandboxDecoder

/** An image host nobody approved; like every test host it resolves to the loopback test server. */
internal const val IMAGE_HOST = "img.example.test"
internal const val OTHER_HOST = "other.example.test"

/** Resolves every `*.example.test` host to the loopback server. Never touches real DNS. */
internal class LoopbackResolver : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.endsWith(".example.test")) listOf(InetAddress.getByName("127.0.0.1")) else throw UnknownHostException(hostname)
}

/** Answers by path and records every request. */
internal class Routes : Dispatcher() {
    private val handlers = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    operator fun set(path: String, handler: (RecordedRequest) -> MockResponse) {
        handlers[path] = handler
    }

    fun serve(path: String, bytes: ByteArray, type: String = "image/png", delayMs: Long = 0) {
        this[path] = {
            if (delayMs > 0) Thread.sleep(delayMs)
            MockResponse.Builder().addHeader("Content-Type", type).body(Buffer().write(bytes)).build()
        }
    }

    fun status(path: String, code: Int) {
        this[path] = { MockResponse.Builder().code(code).build() }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        return handlers[request.url.encodedPath]?.invoke(request) ?: MockResponse.Builder().code(404).build()
    }
}

/** A connector that hands out [sandbox] directly (no binding), as the tests' stand-in for the service. */
internal class DirectConnector(private val sandbox: () -> IImageSandbox?) : SandboxConnector {
    private var current: IImageSandbox? = null

    override suspend fun connect(): IImageSandbox? = current ?: sandbox().also { current = it }

    override fun disconnect() {
        current = null
    }
}

/** The real sandbox binder and decoder, as they run in the isolated process. */
internal fun realSandboxBinder(): IImageSandbox {
    ShadowProcess.setIsIsolated()
    return SandboxBinder(SandboxDecoder()::decode, timeLimitMs = 4_000, kill = {})
}

internal fun realSandboxClient(scope: CoroutineScope): SandboxClient =
    SandboxClient(DirectConnector { realSandboxBinder() }, scope = scope)

/** Runs [block] off the main thread while the main looper keeps running (Robolectric's looper is paused). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> withMainLooper(block: suspend () -> T): T {
    val result: Deferred<T> = CoroutineScope(Dispatchers.IO).async { block() }
    val deadline = System.currentTimeMillis() + 20_000
    while (!result.isCompleted && System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(2)
    }
    return result.getCompleted()
}
