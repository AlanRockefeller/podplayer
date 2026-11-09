/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.net.InetAddress

/** Why an address may not be reached by a feed-supplied URL (SPEC §3). */
enum class BlockReason {
    /** 0.0.0.0/8, `::`. */
    Unspecified,

    /** 127.0.0.0/8, `::1`. */
    Loopback,

    /** 10/8, 172.16/12, 192.168/16. */
    Private,

    /** 169.254/16 (including cloud metadata at 169.254.169.254), fe80::/10. */
    LinkLocal,

    /** 100.64/10, carrier-grade NAT. */
    SharedAddressSpace,

    /** fc00::/7. */
    UniqueLocal,

    /** fec0::/10, deprecated but still routed inside some networks. */
    SiteLocal,

    /** 224/4, ff00::/8. */
    Multicast,

    /** 240/4 including the broadcast address. */
    Reserved,
}

/**
 * Classifies IP addresses for the address filter. IPv6 forms that carry an IPv4 address
 * (IPv4-mapped, IPv4-compatible, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`) are judged by that IPv4 address,
 * so `::ffff:127.0.0.1` or a DNS64-synthesized `64:ff9b::a00:1` can't sneak past.
 */
object AddressClassifier {
    /** Null when the address is public and may be reached. */
    fun blockReason(address: InetAddress): BlockReason? {
        val bytes = address.address
        return when (bytes.size) {
            4 -> ipv4(bytes, 0)
            16 -> ipv6(bytes)
            else -> BlockReason.Reserved
        }
    }

    /**
     * Parses a URL host that is an IP literal (as [okhttp3.HttpUrl.host] gives it: IPv6 without brackets)
     * without ever asking DNS. Returns null for host names and for anything malformed.
     */
    fun parseLiteral(host: String): InetAddress? {
        val bytes = when {
            ':' in host -> parseIpv6(host)
            UrlPolicy.isPlainDottedQuad(host) -> parseIpv4(host)
            else -> null
        } ?: return null
        return InetAddress.getByAddress(bytes)
    }

    private fun ipv4(b: ByteArray, at: Int): BlockReason? {
        val first = b[at].u
        val second = b[at + 1].u
        return when {
            first == 0 -> BlockReason.Unspecified
            first == 127 -> BlockReason.Loopback
            first == 10 -> BlockReason.Private
            first == 172 && second in 16..31 -> BlockReason.Private
            first == 192 && second == 168 -> BlockReason.Private
            first == 169 && second == 254 -> BlockReason.LinkLocal
            first == 100 && second in 64..127 -> BlockReason.SharedAddressSpace
            first in 224..239 -> BlockReason.Multicast
            first >= 240 -> BlockReason.Reserved
            else -> null
        }
    }

    private val nat64Prefix = byteArrayOf(0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

    private fun ipv6(b: ByteArray): BlockReason? {
        val zeroUpTo10 = (0 until 10).all { b[it].u == 0 }
        if (zeroUpTo10 && b[10].u == 0xff && b[11].u == 0xff) return ipv4(b, 12) // ::ffff:a.b.c.d
        if (zeroUpTo10 && b[10].u == 0 && b[11].u == 0) {
            val tail = (12 until 16).map { b[it].u }
            return when (tail) {
                listOf(0, 0, 0, 0) -> BlockReason.Unspecified
                listOf(0, 0, 0, 1) -> BlockReason.Loopback
                else -> ipv4(b, 12) // ::a.b.c.d (IPv4-compatible, deprecated)
            }
        }
        if ((0 until 12).all { b[it] == nat64Prefix[it] }) return ipv4(b, 12)
        if (b[0].u == 0x20 && b[1].u == 0x02) return ipv4(b, 2)
        val first = b[0].u
        val second = b[1].u
        return when {
            first == 0xff -> BlockReason.Multicast
            first == 0xfe && (second and 0xc0) == 0x80 -> BlockReason.LinkLocal
            first == 0xfe && (second and 0xc0) == 0xc0 -> BlockReason.SiteLocal
            (first and 0xfe) == 0xfc -> BlockReason.UniqueLocal
            else -> null
        }
    }

    private fun parseIpv4(host: String): ByteArray = host.split('.').map { it.toInt().toByte() }.toByteArray()

    /** RFC 4291 text form, with `::` compression and an optional dotted-quad tail. No zone ids. */
    private fun parseIpv6(host: String): ByteArray? {
        if (host.length > 45 || host.any { !(it.isHexDigitChar() || it == ':' || it == '.') }) return null
        val halves = host.split("::")
        if (halves.size > 2) return null
        val head = parseGroups(halves[0], allowTail = halves.size == 1) ?: return null
        val tail = if (halves.size == 2) parseGroups(halves[1], allowTail = true) ?: return null else emptyList()
        val total = head.size + tail.size
        if (halves.size == 1 && total != 8) return null
        if (halves.size == 2 && total > 7) return null
        val groups = head + List(8 - total) { 0 } + tail
        return ByteArray(16) { i -> (groups[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
    }

    /** Hex groups separated by ':'; the last may be a dotted quad, which counts as two groups. */
    private fun parseGroups(text: String, allowTail: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val groups = mutableListOf<Int>()
        for ((index, part) in parts.withIndex()) {
            if ('.' in part) {
                if (!allowTail || index != parts.lastIndex || !UrlPolicy.isPlainDottedQuad(part)) return null
                val v4 = parseIpv4(part)
                groups += (v4[0].u shl 8) or v4[1].u
                groups += (v4[2].u shl 8) or v4[3].u
            } else {
                if (part.isEmpty() || part.length > 4) return null
                groups += part.toInt(16)
            }
        }
        return groups
    }

    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val Byte.u: Int get() = toInt() and 0xff
}
