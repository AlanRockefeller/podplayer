/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SPEC §3 address filter: the classes of address a feed-supplied URL must never reach. */
class AddressClassifierTest {
    private fun reason(literal: String): BlockReason? = AddressClassifier.blockReason(InetAddress.getByName(literal))

    /** Builds a real Inet6Address even for mapped forms, which InetAddress.getByName turns into Inet4Address. */
    private fun v6(vararg bytes: Int): InetAddress = Inet6Address.getByAddress(null, ByteArray(16) { bytes[it].toByte() }, -1)

    @Test
    fun ipv4BlockedRanges() {
        val expected = mapOf(
            "0.0.0.0" to BlockReason.Unspecified,
            "0.1.2.3" to BlockReason.Unspecified,
            "127.0.0.1" to BlockReason.Loopback,
            "127.255.255.254" to BlockReason.Loopback,
            "10.0.0.1" to BlockReason.Private,
            "10.255.255.255" to BlockReason.Private,
            "172.16.0.1" to BlockReason.Private,
            "172.31.255.255" to BlockReason.Private,
            "192.168.1.1" to BlockReason.Private,
            "169.254.169.254" to BlockReason.LinkLocal,
            "100.64.0.1" to BlockReason.SharedAddressSpace,
            "100.127.255.255" to BlockReason.SharedAddressSpace,
            "198.18.0.1" to BlockReason.Benchmarking,
            "198.19.255.255" to BlockReason.Benchmarking,
            "224.0.0.1" to BlockReason.Multicast,
            "239.255.255.250" to BlockReason.Multicast,
            "240.0.0.1" to BlockReason.Reserved,
            "255.255.255.255" to BlockReason.Reserved,
        )
        for ((literal, why) in expected) assertEquals(literal, why, reason(literal))
    }

    @Test
    fun ipv4NeighboursOfBlockedRangesArePublic() {
        for (literal in listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255",
            "192.169.0.0", "100.63.255.255", "100.128.0.0", "169.253.255.255", "169.255.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "8.8.8.8", "93.184.215.14", "1.1.1.1")) {
            assertNull(literal, reason(literal))
        }
    }

    @Test
    fun ipv6BlockedRanges() {
        val expected = mapOf(
            "::" to BlockReason.Unspecified,
            "::1" to BlockReason.Loopback,
            "fe80::1" to BlockReason.LinkLocal,
            "febf:ffff::1" to BlockReason.LinkLocal,
            "fc00::" to BlockReason.UniqueLocal,
            "fc00::1" to BlockReason.UniqueLocal,
            "fd12:3456:789a::1" to BlockReason.UniqueLocal,
            "fec0::1" to BlockReason.SiteLocal,
            "ff02::1" to BlockReason.Multicast,
            "ff0e::1" to BlockReason.Multicast,
        )
        for ((literal, why) in expected) assertEquals(literal, why, reason(literal))
    }

    @Test
    fun ipv6PublicAddressesAreAllowed() {
        for (literal in listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "fbff::1", "fe7f::1")) {
            assertNull(literal, reason(literal))
        }
    }

    @Test
    fun ipv4MappedFormsAreJudgedByTheEmbeddedAddress() {
        // ::ffff:127.0.0.1 and ::ffff:10.0.0.1 as genuine Inet6Address objects.
        assertEquals(BlockReason.Loopback, AddressClassifier.blockReason(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1)))
        assertEquals(BlockReason.Private, AddressClassifier.blockReason(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 10, 0, 0, 1)))
        assertNull(AddressClassifier.blockReason(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8)))
        // The JDK turns the textual mapped form into an Inet4Address; that must be caught too.
        assertEquals(BlockReason.LinkLocal, reason("::ffff:169.254.169.254"))
    }

    @Test
    fun ipv4CompatibleFormsAreJudgedByTheEmbeddedAddress() {
        assertEquals(BlockReason.Private, AddressClassifier.blockReason(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 192, 168, 0, 1)))
    }

    @Test
    fun nat64FormsAreJudgedByTheEmbeddedAddress() {
        // 64:ff9b::/96 is how IPv6-only mobile networks reach IPv4 hosts.
        assertEquals(BlockReason.Private, reason("64:ff9b::10.0.0.1"))
        assertEquals(BlockReason.LinkLocal, reason("64:ff9b::169.254.169.254"))
        assertNull(reason("64:ff9b::93.184.215.14"))
    }

    @Test
    fun sixToFourFormsAreJudgedByTheEmbeddedAddress() {
        // 2002:AABB:CCDD::/48 embeds AA.BB.CC.DD.
        assertEquals(BlockReason.Loopback, reason("2002:7f00:1::1"))
        assertEquals(BlockReason.Private, reason("2002:c0a8:101::1"))
        assertNull(reason("2002:5db8:d70e::1"))
    }

    @Test
    fun literalHostsAreParsedWithoutDns() {
        assertEquals(InetAddress.getByName("10.0.0.1"), AddressClassifier.parseLiteral("10.0.0.1"))
        for (literal in listOf("::1", "::", "fc00::1", "2001:db8::8:800:200c:417a", "64:ff9b::10.0.0.1",
            "1:2:3:4:5:6:7:8", "::ffff:1.2.3.4", "fe80::", "1::")) {
            assertEquals(literal, InetAddress.getByName(literal), AddressClassifier.parseLiteral(literal))
        }
        for (notLiteral in listOf("example.com", "localhost", "1::2::3", "12345::", "1:2:3:4:5:6:7:8:9", "1:2:3",
            "::1.2.3", "1.2.3.4::", "::g", "127.1", "")) {
            assertNull(notLiteral, AddressClassifier.parseLiteral(notLiteral))
        }
    }
}
