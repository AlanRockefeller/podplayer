/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §3: the custom Dns refuses private answers, except for the host the owner typed. */
class GatedDnsTest {
    private val publicAddress = InetAddress.getByName("93.184.215.14")
    private val resolver = FakeResolver(
        mapOf(
            "public.example.test" to listOf(publicAddress),
            "nas.example.test" to listOf(InetAddress.getByName("192.168.1.20")),
            "rebind.example.test" to listOf(InetAddress.getByName("169.254.169.254")),
            MIXED_HOST to listOf(InetAddress.getByName("10.1.2.3"), publicAddress),
            "mapped.example.test" to listOf(InetAddress.getByName("::ffff:127.0.0.1")),
        ),
    )

    @Test
    fun publicAnswersPassThrough() {
        assertEquals(listOf(publicAddress), GatedDns(resolver, AddressPolicy.Default).lookup("public.example.test"))
    }

    @Test
    fun privateAnswersAreRefused() {
        val error = assertThrows(BlockedAddressException::class.java) {
            GatedDns(resolver, AddressPolicy.Default).lookup("nas.example.test")
        }
        assertEquals("nas.example.test", error.host)
        assertEquals(BlockReason.Private, error.reason)
        // Callers that only know about UnknownHostException still see a lookup failure.
        assertTrue(error is UnknownHostException)
    }

    @Test
    fun metadataEndpointIsRefused() {
        assertThrows(BlockedAddressException::class.java) {
            GatedDns(resolver, AddressPolicy.Default).lookup("rebind.example.test")
        }
    }

    @Test
    fun mappedLoopbackIsRefused() {
        assertThrows(BlockedAddressException::class.java) {
            GatedDns(resolver, AddressPolicy.Default).lookup("mapped.example.test")
        }
    }

    @Test
    fun privateAddressesInAMixedAnswerAreDropped() {
        assertEquals(listOf(publicAddress), GatedDns(resolver, AddressPolicy.Default).lookup(MIXED_HOST))
    }

    @Test
    fun theOwnerTypedHostMayResolvePrivately() {
        val policy = AddressPolicy.ownerTyped("http://nas.example.test:13378/feed.xml?token=x".toHttpUrl())
        assertEquals(listOf(InetAddress.getByName("192.168.1.20")), GatedDns(resolver, policy).lookup("nas.example.test"))
    }

    @Test
    fun theApprovalCoversOnlyThatHost() {
        val policy = AddressPolicy.ownerTyped("http://nas.example.test/feed.xml".toHttpUrl())
        assertThrows(BlockedAddressException::class.java) { GatedDns(resolver, policy).lookup("rebind.example.test") }
    }

    @Test
    fun hostMatchingIgnoresCase() {
        val policy = AddressPolicy.approvingHosts("NAS.Example.Test")
        assertTrue(policy.approves("nas.example.test"))
        assertFalse(policy.approves("nas.example.test.evil.example"))
        assertFalse(AddressPolicy.Default.approves("nas.example.test"))
    }

    @Test
    fun equalPoliciesMakeEqualResolvers() {
        // OkHttp only shares pooled connections between clients whose Dns is equal.
        val a = GatedDns(resolver, AddressPolicy.approvingHosts("x.example.test"))
        val b = GatedDns(resolver, AddressPolicy.approvingHosts("x.example.test"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, GatedDns(resolver, AddressPolicy.Default))
    }
}
