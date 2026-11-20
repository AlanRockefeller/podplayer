/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.HttpUrl

/**
 * Which hosts a request may reach even when they resolve to a private, loopback or link-local address.
 *
 * Normally none ([Default]). The one exception (SPEC §3, CLAUDE.md) is a feed whose URL the owner typed:
 * requests made for that feed may use that feed's own host, for example a podcast server on the home network.
 * The approval is per host, not per address: it never covers another host, even one on the same machine.
 * The data layer records the approved host with the feed and builds the policy with [ownerTyped].
 */
class AddressPolicy private constructor(private val approvedHosts: Set<String>) {
    fun approves(host: String): Boolean = approvedHosts.isNotEmpty() && host.lowercase() in approvedHosts

    override fun equals(other: Any?) = other is AddressPolicy && other.approvedHosts == approvedHosts

    override fun hashCode() = approvedHosts.hashCode()

    override fun toString() = "AddressPolicy(approved=$approvedHosts)"

    companion object {
        /** No exceptions: every private address is refused. */
        val Default = AddressPolicy(emptySet())

        /** The feed URL the owner typed: its host is approved, nothing else. */
        fun ownerTyped(url: HttpUrl) = AddressPolicy(setOf(url.host))

        /** Hosts already recorded as owner-approved (as [HttpUrl.host] gives them). */
        fun approvingHosts(vararg hosts: String) = AddressPolicy(hosts.map { it.lowercase() }.toSet())
    }
}
