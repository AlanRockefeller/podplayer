/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Application interceptor run for every request and every redirect hop (it sits after
 * [RedirectInterceptor]). It refuses hosts the URL policy doesn't accept and IP-literal hosts in blocked
 * ranges (OkHttp connects to literals without asking the Dns), and it sets our user agent.
 */
internal class UrlGuard(private val policy: AddressPolicy, private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        UrlPolicy.hostProblem(url)?.let { throw DisallowedUrlException(it) }
        if (!policy.approves(url.host)) {
            val literal = AddressClassifier.parseLiteral(url.host)
            if (literal != null) {
                AddressClassifier.blockReason(literal)?.let { throw BlockedAddressException(url.host, it) }
            } else if (':' in url.host) {
                // HttpUrl only produces valid IPv6 hosts; refuse anything we can't classify.
                throw DisallowedUrlException(UrlProblem.Malformed)
            }
        }
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }
}

/**
 * Network interceptor: runs once the socket is connected and before the request is written. It re-checks
 * the address actually connected to, as a second line behind [GatedDns] and [UrlGuard].
 */
internal class ConnectionGuard(private val policy: AddressPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val address = chain.connection()?.route()?.socketAddress?.address
        if (!policy.approves(host)) {
            if (address == null) throw BlockedAddressException(host, null)
            AddressClassifier.blockReason(address)?.let { throw BlockedAddressException(host, it) }
        }
        return chain.proceed(chain.request())
    }
}
