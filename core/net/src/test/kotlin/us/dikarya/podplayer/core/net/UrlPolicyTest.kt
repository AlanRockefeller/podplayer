/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md: feed-supplied URLs are http/https only; links shown to the user are http, https, mailto only. */
class UrlPolicyTest {
    private fun problem(raw: String): UrlProblem? = (UrlPolicy.checkFeedUrl(raw) as? UrlCheck.Rejected)?.problem

    @Test
    fun httpAndHttpsFeedUrlsAreAllowed() {
        for (raw in listOf("http://example.com/feed", "https://example.com/feed.xml?x=1", "HTTPS://Example.COM/a")) {
            assertTrue(raw, UrlPolicy.checkFeedUrl(raw) is UrlCheck.Allowed)
        }
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        val check = UrlPolicy.checkFeedUrl("  https://example.com/feed\n")
        assertEquals("https://example.com/feed".toHttpUrl(), (check as UrlCheck.Allowed).url)
    }

    @Test
    fun otherSchemesAreRefused() {
        val raws = listOf(
            "file:///etc/passwd", "ftp://example.com/feed", "javascript:alert(1)", "content://media/1",
            "intent://scan#Intent;end", "data:text/xml,<rss/>", "jar:file:/x!/y", "gopher://example.com/",
        )
        for (raw in raws) assertEquals(raw, UrlProblem.DisallowedScheme, problem(raw))
    }

    @Test
    fun garbageIsMalformed() {
        for (raw in listOf("", "   ", "example.com/feed", "http://", "http://exa mple.com/", "https://[::1/")) {
            assertEquals(raw, UrlProblem.Malformed, problem(raw))
        }
    }

    @Test
    fun controlCharactersInsideTheUrlAreRefused() {
        assertEquals(UrlProblem.Malformed, problem("https://example.com/fe\ned"))
        assertEquals(UrlProblem.Malformed, problem("https://example.com/feed\u0000"))
    }

    @Test
    fun overlongUrlsAreRefused() {
        val raw = "https://example.com/" + "a".repeat(UrlPolicy.MAX_URL_LENGTH)
        assertEquals(UrlProblem.TooLong, problem(raw))
    }

    @Test
    fun ambiguousNumericHostsAreRefused() {
        // Shorthand and integer forms that resolvers turn into 127.0.0.1 or 10.0.0.1.
        for (raw in listOf("http://127.1/", "http://2130706433/", "http://10.1/feed", "http://0177.0.0.1/", "http://1.2.3.4.5/")) {
            assertEquals(raw, UrlProblem.AmbiguousNumericHost, problem(raw))
        }
    }

    @Test
    fun dottedQuadAndIpv6LiteralsAreSyntacticallyFine() {
        // The address filter, not the URL policy, decides whether these may be reached.
        assertNull(UrlPolicy.hostProblem("http://93.184.215.14/feed".toHttpUrl()))
        assertNull(UrlPolicy.hostProblem("http://[2606:4700::1111]/feed".toHttpUrl()))
    }

    @Test
    fun linksMayBeHttpHttpsOrMailto() {
        for (raw in listOf("https://example.com", "http://example.com/a?b", "mailto:host@example.com", "MAILTO:x@y.z")) {
            assertTrue(raw, UrlPolicy.isAllowedLink(raw))
        }
    }

    @Test
    fun linksWithOtherSchemesAreRefused() {
        val raws = listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", " javascript:alert(1)", "java\tscript:alert(1)",
            "java\nscript:alert(1)", "intent://x#Intent;scheme=http;end", "tel:123", "sms:123", "file:///sdcard",
            "content://contacts", "data:text/html,hi", "vbscript:x", "market://details?id=x", "/relative/path",
            "//example.com/no-scheme", "", "\u0000javascript:x",
        )
        for (raw in raws) assertFalse(raw, UrlPolicy.isAllowedLink(raw))
    }

    @Test
    fun allowedUrlsDoNotPrintTheirSecrets() {
        val check = UrlPolicy.checkFeedUrl("https://u:p@example.com/feed?token=abc")
        assertFalse(check.toString(), "token" in check.toString() || "u:p" in check.toString())
    }
}
