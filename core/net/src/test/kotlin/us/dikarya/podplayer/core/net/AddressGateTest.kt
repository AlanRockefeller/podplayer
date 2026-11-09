/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CLAUDE.md: feed-supplied URLs must not reach private, loopback, link-local or unique-local addresses,
 * checked after DNS and after every redirect, unless the owner typed that exact feed URL.
 *
 * The test server listens on 127.0.0.1. [FEED_HOST] stands for the owner's typed host; every other
 * name or literal that leads to loopback must be refused before a request reaches the server.
 */
class AddressGateTest {
    private lateinit var server: MockWebServer
    private val gate = testGate()

    @Before
    fun setUp() {
        server = startServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fetchFromFeedHost(): FetchResult = runBlocking {
        gate.fetch(server.urlOn(FEED_HOST, "/feed"), BodyLimit.FEED, approvedFeedHost)
    }

    private fun assertRedirectRefused(location: String, reason: BlockReason) {
        server.enqueue(redirect(302, location))
        server.enqueue(ok("must not be served"))
        val result = fetchFromFeedHost()
        val error = (result as FetchResult.Failure).error
        assertTrue("$location gave $error", error is NetError.BlockedAddress)
        assertEquals(location, reason, (error as NetError.BlockedAddress).reason)
        assertEquals("only the redirecting request reached the server", 1, server.requestCount)
    }

    @Test
    fun redirectToLoopbackLiteralIsRefused() {
        // Same machine and port as the typed host, but a different host: still refused.
        assertRedirectRefused("http://127.0.0.1:${server.port}/admin", BlockReason.Loopback)
    }

    @Test
    fun redirectToPrivateTenNetIsRefused() {
        assertRedirectRefused("http://10.0.0.7/feed", BlockReason.Private)
    }

    @Test
    fun redirectToCloudMetadataIsRefused() {
        assertRedirectRefused("http://169.254.169.254/latest/meta-data/", BlockReason.LinkLocal)
    }

    @Test
    fun redirectToIpv6LoopbackIsRefused() {
        assertRedirectRefused("http://[::1]:${server.port}/", BlockReason.Loopback)
    }

    @Test
    fun redirectToUniqueLocalIsRefused() {
        assertRedirectRefused("http://[fc00::1]/", BlockReason.UniqueLocal)
    }

    @Test
    fun redirectToIpv4MappedLoopbackIsRefused() {
        assertRedirectRefused("http://[::ffff:127.0.0.1]:${server.port}/", BlockReason.Loopback)
    }

    @Test
    fun redirectToANameThatResolvesPrivatelyIsRefused() {
        assertRedirectRefused("http://$EVIL_HOST:${server.port}/", BlockReason.Loopback)
    }

    @Test
    fun redirectToAnAmbiguousNumericHostIsRefused() {
        server.enqueue(redirect(302, "http://127.1:${server.port}/"))
        val error = (fetchFromFeedHost() as FetchResult.Failure).error
        assertTrue("$error", error is NetError.InvalidUrl || error is NetError.BlockedAddress)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun typedLocalHostIsAllowed() {
        server.enqueue(ok("<rss/>"))
        val result = fetchFromFeedHost()
        assertEquals("<rss/>", (result as FetchResult.Success).body.decodeToString())
    }

    @Test
    fun typedLocalHostMayRedirectWithinItself() {
        server.enqueue(redirect(302, "/moved"))
        server.enqueue(ok("<rss/>"))
        assertTrue(fetchFromFeedHost() is FetchResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun withoutApprovalTheSameHostIsRefused() = runBlocking {
        val result = gate.fetch(server.urlOn(FEED_HOST, "/feed"), BodyLimit.FEED, AddressPolicy.Default)
        assertTrue((result as FetchResult.Failure).error is NetError.BlockedAddress)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun approvalForOneHostDoesNotCoverAnother() = runBlocking {
        val result = gate.fetch(server.urlOn(EVIL_HOST, "/feed"), BodyLimit.FEED, approvedFeedHost)
        assertTrue((result as FetchResult.Failure).error is NetError.BlockedAddress)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loopbackLiteralIsRefusedWithoutApproval() = runBlocking {
        val result = gate.fetch(server.urlOn("127.0.0.1", "/feed"), BodyLimit.FEED, AddressPolicy.Default)
        val error = (result as FetchResult.Failure).error
        assertEquals(NetError.BlockedAddress("127.0.0.1", BlockReason.Loopback), error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun typedLoopbackLiteralIsAllowedForThatLiteralOnly() = runBlocking {
        server.enqueue(ok("<rss/>"))
        val typed = server.urlOn("127.0.0.1", "/feed")
        val result = gate.fetch(typed, BodyLimit.FEED, AddressPolicy.ownerTyped(typed))
        assertTrue("$result", result is FetchResult.Success)
        // The same server under another literal form is a different host and stays refused.
        val other = gate.fetch(server.urlOn("::ffff:127.0.0.2", "/feed"), BodyLimit.FEED, AddressPolicy.ownerTyped(typed))
        assertEquals(NetError.BlockedAddress("127.0.0.2", BlockReason.Loopback), (other as FetchResult.Failure).error)
    }

    @Test
    fun enclosureOnAPrivateAddressIsRefused() = runBlocking {
        // SPEC §14 hostile corpus: a feed's enclosure pointing into the owner's network.
        val result = gate.fetch("http://192.168.0.1/router.mp3".toHttpUrl(), BodyLimit.IMAGE, approvedFeedHost)
        assertEquals(NetError.BlockedAddress("192.168.0.1", BlockReason.Private), (result as FetchResult.Failure).error)
    }

    @Test
    fun rawCallFactoryRefusesHostileRedirectsToo() {
        // ExoPlayer uses the Call.Factory directly; it gets the same redirect checks.
        server.enqueue(redirect(302, "http://127.0.0.1:${server.port}/secret"))
        server.enqueue(ok("must not be served"))
        val call = gate.callFactory(approvedFeedHost).newCall(Request.Builder().url(server.urlOn(FEED_HOST)).build())
        assertThrows(BlockedAddressException::class.java) { call.execute() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rawCallFactoryFollowsAllowedRedirects() {
        server.enqueue(redirect(302, server.urlOn(CDN_HOST, "/a.mp3").toString()))
        server.enqueue(ok("audio"))
        val call = gate.callFactory(approvedFeedAndCdn).newCall(Request.Builder().url(server.urlOn(FEED_HOST)).build())
        call.execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("audio", response.body.string())
            assertEquals(CDN_HOST, response.request.url.host)
        }
    }

    @Test
    fun connectionGuardRefusesAPrivateSocketEvenIfDnsLetItThrough() {
        // Defence in depth: a client whose Dns does not filter still cannot talk to loopback.
        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(LOOPBACK) })
            .addNetworkInterceptor(ConnectionGuard(AddressPolicy.Default))
            .build()
        server.enqueue(ok())
        val call = client.newCall(Request.Builder().url(server.urlOn(EVIL_HOST)).build())
        val error = assertThrows(IOException::class.java) { call.execute() }
        assertTrue("$error", error is BlockedAddressException)
        assertEquals(0, server.requestCount)
    }
}
