/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a followed redirect carries in credentials. The test server has no TLS, so the https cases go
 * through [RedirectInterceptor.followUp] directly.
 */
class RedirectCredentialsTest {
    private fun request(url: String, authorization: String? = null) =
        Request.Builder().url(url).apply { authorization?.let { header("Authorization", it) } }.build()

    private fun follow(from: Request, to: String) = RedirectInterceptor.followUp(from, to.toHttpUrl(), 302)

    @Test
    fun userinfoInAnHttpsLocationBecomesBasicAuthForThatHost() {
        val next = follow(request("https://feeds.example/feed", "Basic owner"), "https://bob:pw@cdn.example/feed")
        assertEquals("Basic Ym9iOnB3", next.header("Authorization"))
        assertEquals("", next.url.username)
    }

    @Test
    fun userinfoInAnHttpLocationIsNeverSentInTheClear() {
        for (from in listOf("https://feeds.example/feed", "http://feeds.example/feed")) {
            val next = follow(request(from), "http://bob:pw@cdn.example/feed")
            assertNull(from, next.header("Authorization"))
            assertEquals(from, "", next.url.username)
            assertEquals(from, "", next.url.password)
        }
    }

    @Test
    fun userinfoInAnHttpLocationOnTheSameHostIsNotSentEither() {
        val next = follow(request("http://feeds.example/feed"), "http://bob:pw@feeds.example/other")
        assertNull(next.header("Authorization"))
        assertEquals("", next.url.username)
    }

    @Test
    fun theOwnersCredentialsAreDroppedWhenARedirectLeavesHttpsForHttpOnTheSameHost() {
        val next = follow(request("https://feeds.example/feed", "Basic owner"), "http://feeds.example/feed")
        assertNull(next.header("Authorization"))
    }

    @Test
    fun anOwnerWhoTypedAnHttpFeedKeepsTheirCredentialsOnTheSameOrigin() {
        // The owner chose cleartext for this feed; the gate doesn't second-guess that on its own origin.
        val next = follow(request("http://feeds.example/feed", "Basic owner"), "http://feeds.example/moved")
        assertEquals("Basic owner", next.header("Authorization"))
    }
}
