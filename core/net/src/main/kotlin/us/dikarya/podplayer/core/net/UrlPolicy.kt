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
    private val plainHost = Regex("^[A-Za-z0-9._-]+$")
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
        // OkHttp, like a browser, also reads `https:/host` and `https:\\host`; a feed's URL must say `//`.
        if (!trimmed.startsWith("//", startIndex = scheme.length + 1)) return UrlCheck.Rejected(UrlProblem.Malformed)
        val url = trimmed.toHttpUrlOrNull() ?: return UrlCheck.Rejected(UrlProblem.Malformed)
        hostProblem(url)?.let { return UrlCheck.Rejected(it) }
        return UrlCheck.Allowed(url)
    }

    /** A problem with an already parsed URL's host, or null. Used again on every redirect hop. */
    fun hostProblem(url: HttpUrl): UrlProblem? {
        val host = url.host
        if (host.isEmpty()) return UrlProblem.Malformed
        // OkHttp keeps punctuation such as `'` in a host name; real ones are letters, digits, `-`, `_` and dots
        // (IDNs arrive as xn-- punycode), or an IPv6 literal.
        if (':' !in host && !plainHost.matches(host)) return UrlProblem.Malformed
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
 * everything up to the last `@` of what looks like the authority. Like OkHttp, it reads any run of `/` and `\`
 * after the scheme as the start of the authority, so `https:///user:pw@host` loses its userinfo too.
 */
fun redact(url: String): String {
    val cut = listOf(url.indexOf('?'), url.indexOf('#')).filter { it >= 0 }.minOrNull() ?: url.length
    val head = url.substring(0, cut)
    val scheme = schemeBeforeSlashes.find(head)
    val authorityStart = if (scheme != null) {
        var start = scheme.range.last + 1
        while (start < head.length && (head[start] == '/' || head[start] == '\\')) start++
        start
    } else {
        val slashes = head.indexOf("//")
        if (slashes >= 0 && '/' !in head.substring(0, slashes)) slashes + 2 else 0
    }
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

/** A scheme followed by at least one slash or backslash, at the start of a string (after any whitespace). */
private val schemeBeforeSlashes = Regex("""^\s*[A-Za-z][A-Za-z0-9+.-]*:(?=[/\\])""")

/**
 * Redacts every URL inside free text, such as an exception message, before it is logged or shown.
 *
 * A URL is `scheme://` (as `\b[a-z][a-z0-9+.-]*://`) followed by everything up to whitespace, a double quote or
 * an angle bracket: a parenthesis or an apostrophe may be part of a URL (`/o'brien/feed`), and stopping early
 * there would leave a query string behind in the text. Found in one forward scan rather than with a regex,
 * which backtracks quadratically over hostile text such as `a.a.a.a...`.
 */
fun scrubUrls(text: String): String {
    val out = StringBuilder(text.length)
    var copied = 0
    var search = 0
    while (true) {
        val (start, afterSlashes) = nextScheme(text, search, copied) ?: break
        var end = afterSlashes
        while (end < text.length && !text[end].endsUrlInText()) end++
        search = end
        if (end == afterSlashes) continue // `scheme://` with nothing after it
        out.append(text, copied, start).append(scrubCandidate(text.substring(start, end)))
        copied = end
    }
    return out.append(text, copied, text.length).toString()
}

private fun Char.endsUrlInText() = this in " \t\n\u000B\u000C\r\"<>"

/**
 * One run of URL characters may hold several URLs (`'https://a/x','https://u:pw@b/y'`). Each one is redacted.
 * Once a query or fragment has started, everything after it goes, nested URLs included.
 */
private fun scrubCandidate(candidate: String): String {
    // One forward pass, so hostile runs (`a://a://...`, `a.a.a...`) stay linear.
    val out = StringBuilder()
    var start = 0
    var searchFrom = candidate.indexOf("://") + 3 // the candidate's own scheme
    while (true) {
        val next = nextScheme(candidate, searchFrom, start + 1)
        val end = next?.first ?: candidate.length
        val queryOrFragment = (start until end).any { candidate[it] == '?' || candidate[it] == '#' }
        if (next == null || queryOrFragment) return out.append(redact(candidate.substring(start))).toString()
        out.append(redact(candidate.substring(start, end)))
        start = end
        searchFrom = next.second
    }
}

/**
 * The next `scheme://` in [text] whose `://` is at or after [from] and whose scheme starts at or after [minStart]:
 * the scheme's start and the index just past `://`, or null. The scheme is the longest run before `://` that
 * starts with a letter at a word boundary, as `\b[a-z][a-z0-9+.-]*://` would match it. Each character is looked
 * at a bounded number of times: runs of scheme characters never contain `/`, so walks back never overlap.
 */
private fun nextScheme(text: String, from: Int, minStart: Int): Pair<Int, Int>? {
    var search = from
    while (true) {
        val slashes = text.indexOf("://", search)
        if (slashes < 0) return null
        var s = slashes
        var start = -1
        while (s > minStart && text[s - 1].isSchemeChar()) {
            s--
            if (text[s].isAsciiLetter() && (s == 0 || !text[s - 1].isWordChar())) start = s
        }
        if (start >= 0) return start to slashes + 3
        search = slashes + 1
    }
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isWordChar() = isAsciiLetter() || this in '0'..'9' || this == '_'

private fun Char.isSchemeChar() = isAsciiLetter() || this in '0'..'9' || this == '+' || this == '.' || this == '-'
