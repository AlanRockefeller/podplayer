/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.net.InetAddress
import okhttp3.Dns

/**
 * The address filter for host names (SPEC §3). It drops every resolved address the classifier blocks and
 * fails when none is left, unless the policy approves the host. OkHttp connects only to the addresses this
 * returns, so a DNS answer that changes between check and use (rebinding) can't get around it.
 *
 * IP literals never reach a Dns in OkHttp; [UrlGuard] checks those.
 */
internal class GatedDns(private val upstream: Dns, private val policy: AddressPolicy) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = upstream.lookup(hostname)
        if (policy.approves(hostname)) return addresses
        val reasons = addresses.map { AddressClassifier.blockReason(it) }
        val allowed = addresses.filterIndexed { index, _ -> reasons[index] == null }
        if (allowed.isEmpty()) throw BlockedAddressException(hostname, reasons.firstOrNull())
        return allowed
    }

    // OkHttp reuses a pooled connection only for an equal Dns, so equal policies share connections
    // and different policies never do.
    override fun equals(other: Any?) = other is GatedDns && other.upstream == upstream && other.policy == policy

    override fun hashCode() = 31 * upstream.hashCode() + policy.hashCode()
}
