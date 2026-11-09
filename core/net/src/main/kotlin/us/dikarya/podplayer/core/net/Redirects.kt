/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** One followed redirect. */
class RedirectHop(val from: HttpUrl, val to: HttpUrl, val code: Int) {
    /** 301 and 308 move a resource for good; 302, 303 and 307 don't. */
    val permanent: Boolean get() = code == 301 || code == 308

    override fun toString() = "$code ${redact(from)} -> ${redact(to)}"
}

/** The redirects a request went through. SPEC §3: a 301/308 on a feed URL updates the stored URL. */
class RedirectChain(val start: HttpUrl, val hops: List<RedirectHop>) {
    val finalUrl: HttpUrl get() = hops.lastOrNull()?.to ?: start

    val finalHopPermanent: Boolean get() = hops.lastOrNull()?.permanent == true

    /**
     * Where [start] now permanently lives: the end of the leading run of permanent hops, or null when the
     * first hop was temporary or there was no redirect. For A -301-> B -302-> C this is B: A moved to B
     * for good, and B only temporarily serves C, so the feed's new address is B.
     */
    val permanentUrl: HttpUrl?
        get() = hops.takeWhile { it.permanent }.lastOrNull()?.to

    /**
     * True when a hop left https for http. The gate allows it (SPEC §3 allows cleartext), but a feed that
     * does this is no more secure than a plain http one, and the UI marks it so.
     */
    val downgraded: Boolean
        get() = hops.any { it.from.isHttps && !it.to.isHttps }

    override fun toString() = "RedirectChain(${redact(start)}, $hops)"
}

/** The chain a response came through; set by [RedirectInterceptor] on the final request. */
val Response.redirectChain: RedirectChain
    get() = request.tag(RedirectChain::class) ?: RedirectChain(request.url, emptyList())

/**
 * Follows redirects by hand (the client's own following is off): at most [maxRedirects] hops, each one
 * going back through [UrlGuard], [GatedDns] and [ConnectionGuard]. Only GET and HEAD are followed.
 * Credentials are dropped when the host, port or scheme changes.
 *
 * A URL with userinfo (`https://user:pass@host/feed`, as some private feeds are written) is sent as HTTP
 * Basic auth to that host, since OkHttp never sends userinfo itself, and the userinfo leaves the URL, so it
 * is dropped with the header when a redirect changes host. An explicit `Authorization` header wins.
 */
internal class RedirectInterceptor(private val maxRedirects: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val start = chain.request().url
        var request = userinfoAsBasicAuth(chain.request())
        val hops = mutableListOf<RedirectHop>()
        while (true) {
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES || request.method !in FOLLOWED_METHODS) {
                return response.newBuilder()
                    .request(request.newBuilder().tag(RedirectChain::class, RedirectChain(start, hops.toList())).build())
                    .build()
            }
            val location = response.header("Location")
            response.close()
            if (hops.size >= maxRedirects) throw TooManyRedirectsException(maxRedirects)
            if (location.isNullOrBlank()) throw BadRedirectException("redirect without a Location header")
            val target = request.url.resolve(location)
                ?: throw BadRedirectException("redirect to a URL that is not http or https")
            hops += RedirectHop(request.url, target, response.code)
            request = nextRequest(request, target, response.code)
        }
    }

    private fun userinfoAsBasicAuth(request: Request): Request {
        val url = request.url
        if (url.username.isEmpty() && url.password.isEmpty()) return request
        val builder = request.newBuilder().url(url.newBuilder().username("").password("").build())
        if (request.header("Authorization") == null) {
            builder.header("Authorization", Credentials.basic(url.username, url.password, Charsets.UTF_8))
        }
        return builder.build()
    }

    private fun nextRequest(previous: Request, target: HttpUrl, code: Int): Request {
        val builder = previous.newBuilder().url(target)
        if (code == 303 && previous.method != "HEAD") builder.get()
        val sameOrigin = target.scheme == previous.url.scheme && target.host == previous.url.host &&
            target.port == previous.url.port
        if (!sameOrigin) CREDENTIAL_HEADERS.forEach { builder.removeHeader(it) }
        return builder.build()
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val FOLLOWED_METHODS = setOf("GET", "HEAD")
        val CREDENTIAL_HEADERS = listOf("Authorization", "Cookie", "Proxy-Authorization")
    }
}
