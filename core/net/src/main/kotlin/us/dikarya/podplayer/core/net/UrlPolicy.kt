/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why a feed-supplied URL was refused. */
enum class UrlProblem {
    /** Not a URL we can parse, or it has no host. */
    Malformed,

    /** Anything other than http or https. */
    DisallowedScheme,

    /** Longer than [UrlPolicy.MAX_URL_LENGTH]. */
    TooLong,

    /**
     * A numeric host that isn't a plain dotted quad (`127.1`, `2130706433`, `0177.0.0.1`).
     * Resolvers read these as IP addresses in surprising ways, and no real feed uses them.
     */
    AmbiguousNumericHost,
}

sealed interface UrlCheck {
    class Allowed(val url: HttpUrl) : UrlCheck {
        override fun toString() = "Allowed(${redact(url)})"
    }

    data class Rejected(val problem: UrlProblem) : UrlCheck
}

/** SPEC §3 and CLAUDE.md URL rules. */
object UrlPolicy {
    const val MAX_URL_LENGTH = 8 * 1024

    private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val numericHost = Regex("^[0-9.]+$")
    private val dottedQuadPart = Regex("^(0|[1-9][0-9]{0,2})$")
    private val linkSchemes = setOf("http", "https", "mailto")

    /** Checks a URL that came from a feed, an OPML file, a redirect or the owner. */
    fun checkFeedUrl(raw: String): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.length > MAX_URL_LENGTH) return UrlCheck.Rejected(UrlProblem.TooLong)
        if (trimmed.any { it < ' ' || it == '\u007f' }) return UrlCheck.Rejected(UrlProblem.Malformed)
        val scheme = schemePrefix.find(trimmed)?.groupValues?.get(1)?.lowercase()
            ?: return UrlCheck.Rejected(UrlProblem.Malformed)
        if (scheme != "http" && scheme != "https") return UrlCheck.Rejected(UrlProblem.DisallowedScheme)
        val url = trimmed.toHttpUrlOrNull() ?: return UrlCheck.Rejected(UrlProblem.Malformed)
        hostProblem(url)?.let { return UrlCheck.Rejected(it) }
        return UrlCheck.Allowed(url)
    }

    /** A problem with an already parsed URL's host, or null. Used again on every redirect hop. */
    fun hostProblem(url: HttpUrl): UrlProblem? {
        val host = url.host
        if (host.isEmpty()) return UrlProblem.Malformed
        if (numericHost.matches(host) && !isPlainDottedQuad(host)) return UrlProblem.AmbiguousNumericHost
        return null
    }

    /**
     * Whether a link may be shown to the owner as tappable: http, https and mailto only.
     * Whitespace and control characters are removed first, as browsers do, so `java\tscript:` is caught.
     */
    fun isAllowedLink(raw: String): Boolean {
        val cleaned = raw.filterNot { it <= ' ' || it == '\u007f' }
        val scheme = schemePrefix.find(cleaned)?.groupValues?.get(1)?.lowercase() ?: return false
        return scheme in linkSchemes && cleaned.length > scheme.length + 1
    }

    internal fun isPlainDottedQuad(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { dottedQuadPart.matches(it) && it.toInt() <= 255 }
    }
}

/**
 * Removes the query, fragment and userinfo from a URL so it can be logged (CLAUDE.md: feed URLs are secrets).
 * Works on malformed input too: whatever it can't parse, it still cuts at the first `?` or `#` and drops
 * everything up to the last `@` of what looks like the authority.
 */
fun redact(url: String): String {
    val cut = listOf(url.indexOf('?'), url.indexOf('#')).filter { it >= 0 }.minOrNull() ?: url.length
    val head = url.substring(0, cut)
    val slashes = head.indexOf("//")
    val authorityStart = if (slashes >= 0 && '/' !in head.substring(0, slashes)) slashes + 2 else 0
    val authorityEnd = head.indexOfAny(charArrayOf('/', '\\'), authorityStart).let { if (it < 0) head.length else it }
    val at = head.lastIndexOf('@', authorityEnd - 1)
    return if (at >= authorityStart) head.substring(0, authorityStart) + head.substring(at + 1) else head
}

/** [redact] for a parsed URL: scheme, host, non-default port and path. */
fun redact(url: HttpUrl): String = buildString {
    append(url.scheme).append("://")
    append(if (':' in url.host) "[${url.host}]" else url.host)
    if (url.port != HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
    append(url.encodedPath)
}

// Greedy up to whitespace or quotes: a parenthesis may be part of a URL, and stopping early
// there could leave a query string behind in the text.
private val urlInText = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]+""")

/** Redacts every URL inside free text, such as an exception message, before it is logged or shown. */
fun scrubUrls(text: String): String = urlInText.replace(text) { redact(it.value) }
