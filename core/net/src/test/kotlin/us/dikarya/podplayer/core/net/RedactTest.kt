/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** CLAUDE.md: feed URLs are secrets; logs strip query, fragment and userinfo. */
class RedactTest {
    @Test
    fun stripsQuery() {
        assertEquals("https://feeds.example.com/private.xml", redact("https://feeds.example.com/private.xml?auth=s3cret"))
    }

    @Test
    fun stripsFragment() {
        assertEquals("https://example.com/feed", redact("https://example.com/feed#token=abc"))
    }

    @Test
    fun stripsUserinfo() {
        assertEquals("https://example.com/feed", redact("https://alice:hunter2@example.com/feed"))
        assertEquals("https://example.com/feed", redact("https://token@example.com/feed"))
    }

    @Test
    fun stripsAllThreeTogether() {
        val raw = "http://user:pa%40ss@cdn.example.com:8080/a/b.mp3?sig=1&exp=2#t=30"
        assertEquals("http://cdn.example.com:8080/a/b.mp3", redact(raw))
    }

    @Test
    fun anAtSignInTheUserinfoDoesNotLeakThePassword() {
        // The last '@' before the path ends the userinfo.
        assertEquals("https://example.com/feed", redact("https://a@b:c@d@example.com/feed"))
    }

    @Test
    fun anAtSignInThePathIsKept() {
        assertEquals("https://example.com/@show/feed", redact("https://example.com/@show/feed"))
    }

    @Test
    fun aFragmentBeforeAQuestionMarkStillCutsEverything() {
        assertEquals("https://example.com/feed", redact("https://example.com/feed#a?b"))
    }

    @Test
    fun plainUrlsAreUnchanged() {
        assertEquals("https://example.com/feed.xml", redact("https://example.com/feed.xml"))
    }

    @Test
    fun malformedInputNeverLeaksASecret() {
        val expected = mapOf(
            "not a url?token=1" to "not a url",
            "//host/path?k=v" to "//host/path",
            "user:pw@host/feed" to "host/feed",
            "ht!tp://x@y/z?q" to "ht!tp://y/z",
            "?only" to "",
            "mailto:someone@example.com" to "example.com",
        )
        for ((raw, redacted) in expected) {
            val out = redact(raw)
            assertEquals(raw, redacted, out)
            assertFalse(out, '?' in out || '#' in out || '@' in out)
        }
    }

    @Test
    fun extraSlashesBeforeTheAuthorityDoNotHideTheUserinfo() {
        // OkHttp reads `https:///alice:SECRET@example.com` as userinfo alice:SECRET on example.com, and the
        // feed URL check accepts it, so a stored feed URL can look like this.
        assertEquals("https:///example.com/feed", redact("https:///alice:SECRET@example.com/feed?token=x"))
        assertEquals("https:////example.com/feed", redact("https:////alice:SECRET@example.com/feed"))
        assertEquals("https://\\example.com/feed", redact("https://\\alice:SECRET@example.com/feed"))
        assertEquals("https:/example.com/feed", redact("https:/alice:SECRET@example.com/feed"))
        assertEquals("https:\\\\example.com/feed", redact("https:\\\\alice:SECRET@example.com/feed"))
        assertEquals(" https:///example.com/feed", redact(" https:///alice:SECRET@example.com/feed"))
        assertEquals("https://example.com/feed", redact("https:///alice:SECRET@example.com/feed".toHttpUrl()))
    }

    @Test
    fun backslashesEndTheAuthorityLikeInBrowsers() {
        assertEquals("https://example.com\\feed", redact("https://u:p@example.com\\feed?x=1"))
    }

    @Test
    fun parsedUrlsAreRedactedToo() {
        val url = "https://bob:pw@example.com/feed.xml?key=1#frag".toHttpUrl()
        assertEquals("https://example.com/feed.xml", redact(url))
    }

    @Test
    fun parsedUrlsKeepANonDefaultPort() {
        assertEquals("http://example.com:8080/feed", redact("http://example.com:8080/feed?a=b".toHttpUrl()))
    }

    @Test
    fun scrubRedactsEveryUrlInsideText() {
        val text = "failed: unexpected end of stream on https://cdn.example.com/a.mp3?token=XYZ (and http://u:p@h.example/x?y)"
        val scrubbed = scrubUrls(text)
        assertEquals("failed: unexpected end of stream on https://cdn.example.com/a.mp3 (and http://h.example/x", scrubbed)
    }

    @Test
    fun scrubDoesNotStopAtAParenthesisInsideAUrl() {
        assertEquals("see https://x.example/a(b)", scrubUrls("see https://x.example/a(b)?token=1"))
    }

    @Test
    fun scrubDoesNotStopAtAnApostropheInsideAUrl() {
        assertEquals("failed https://example.com/o'brien/feed", scrubUrls("failed https://example.com/o'brien/feed?token=SECRET"))
    }

    @Test
    fun scrubStillRedactsEachOfSeveralUrlsJoinedByQuotes() {
        // Apostrophes no longer end a URL, so quoted URLs written side by side arrive as one candidate.
        val scrubbed = scrubUrls("tried 'https://a.example/x','https://u:pw@b.example/y' and 'https://c.example/z?k=SECRET'")
        assertFalse(scrubbed, "pw" in scrubbed || "SECRET" in scrubbed || '@' in scrubbed)
        assertEquals("tried 'https://a.example/x','https://b.example/y' and 'https://c.example/z", scrubbed)
    }

    @Test
    fun scrubDropsAUrlNestedInAQuery() {
        assertEquals("go https://t.example/r", scrubUrls("go https://t.example/r?u=https://u:pw@cdn.example/a.mp3"))
    }

    @Test
    fun scrubLeavesTextWithoutUrlsAlone() {
        assertEquals("timeout", scrubUrls("timeout"))
    }
}
