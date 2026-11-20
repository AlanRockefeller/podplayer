/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.net.InetAddress
import java.net.UnknownHostException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl
import okio.Buffer

/** Every test server listens on loopback, so the hosts below all resolve there. */
internal val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** The host the owner "typed" in most tests. */
internal const val FEED_HOST = "feeds.example.test"

/** A second host the owner also approved, for cross-host redirects that should work. */
internal const val CDN_HOST = "cdn.example.test"

/** A host nobody approved. It resolves to loopback, so only the address filter keeps it out. */
internal const val EVIL_HOST = "evil.example.test"

/** A name that resolves to a mix of public and private addresses. */
internal const val MIXED_HOST = "mixed.example.test"

/** Resolves a fixed table of names; anything else is unknown. Never touches real DNS. */
internal class FakeResolver(private val table: Map<String, List<InetAddress>>) : Dns {
    val lookups = mutableListOf<String>()

    override fun lookup(hostname: String): List<InetAddress> {
        synchronized(lookups) { lookups += hostname }
        return table[hostname] ?: throw UnknownHostException(hostname)
    }
}

internal fun testResolver() = FakeResolver(
    mapOf(
        FEED_HOST to listOf(LOOPBACK),
        CDN_HOST to listOf(LOOPBACK),
        EVIL_HOST to listOf(LOOPBACK),
        MIXED_HOST to listOf(InetAddress.getByName("10.1.2.3"), InetAddress.getByName("93.184.215.14")),
    ),
)

internal fun testGate(
    config: NetConfig = NetConfig(appVersion = "1.2.3"),
    resolver: Dns = testResolver(),
) = NetGate(config, resolver)

internal fun startServer(): MockWebServer = MockWebServer().apply { start(LOOPBACK, 0) }

/** The server's URL, but addressed by [host] so the fake resolver decides where it goes. */
internal fun MockWebServer.urlOn(host: String, path: String = "/"): HttpUrl =
    url(path).newBuilder().host(host).build()

internal fun redirect(code: Int, location: String): MockResponse =
    MockResponse.Builder().code(code).addHeader("Location", location).build()

internal fun ok(body: String = "ok"): MockResponse = MockResponse.Builder().body(body).build()

internal fun okBytes(bytes: ByteArray, configure: MockResponse.Builder.() -> Unit = {}): MockResponse =
    MockResponse.Builder().body(Buffer().write(bytes)).apply(configure).build()

internal val approvedFeedHost = AddressPolicy.approvingHosts(FEED_HOST)
internal val approvedFeedAndCdn = AddressPolicy.approvingHosts(FEED_HOST, CDN_HOST)

/** Deterministic, non-repeating-looking bytes, so an offset mistake shows up as a content mismatch. */
internal fun testBytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + i / 251) and 0xFF).toByte() }
