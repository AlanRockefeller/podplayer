/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetError

/** SPEC §12: Apple's podcast search is the only search endpoint; it goes through the gate like everything else. */
class AppleSearchTest {
    private class Call(val url: HttpUrl, val limit: BodyLimit, val policy: AddressPolicy)

    private val calls = mutableListOf<Call>()

    private fun search(answer: FetchResult): AppleSearch = AppleSearch { url, limit, policy ->
        calls += Call(url, limit, policy)
        answer
    }

    private fun json(body: String) = success(body.toByteArray(), "application/json")

    @Test
    fun theRequestIsAppleSearchWithTheTermAndNothingElse() = runBlocking {
        search(json("""{"results":[]}""")).search("the daily & more")
        val call = calls.single()
        assertEquals("https", call.url.scheme)
        assertEquals("itunes.apple.com", call.url.host)
        assertEquals(listOf("search"), call.url.pathSegments)
        assertEquals(setOf("media", "entity", "term"), call.url.queryParameterNames)
        assertEquals("podcast", call.url.queryParameter("media"))
        assertEquals("podcast", call.url.queryParameter("entity"))
        assertEquals("the daily & more", call.url.queryParameter("term"))
        assertEquals("no private address is ever approved for search", AddressPolicy.Default, call.policy)
        assertEquals(AppleSearch.LIMIT.maxBytes, call.limit.maxBytes)
    }

    @Test
    fun resultsKeepOnlyUsableFeeds() = runBlocking {
        val body = """
            {"resultCount": 6, "results": [
              {"collectionName": "Good Show", "artistName": "Someone", "feedUrl": "https://feeds.example.com/good.xml",
               "artworkUrl600": "https://is1.example.com/600.jpg", "artworkUrl100": "https://is1.example.com/100.jpg", "extra": {"x": [1, 2]}},
              {"collectionName": "No feed", "artistName": "Nobody"},
              {"collectionName": "Script", "feedUrl": "javascript:alert(1)"},
              {"collectionName": "Local file", "feedUrl": "file:///data/data/x.db"},
              {"collectionName": "Plain http", "feedUrl": "http://old.example.com/rss", "artworkUrl600": "file:///x.jpg"},
              {"collectionName": "Good Show again", "feedUrl": "https://feeds.example.com/good.xml"}
            ]}
        """.trimIndent()
        val hits = (search(json(body)).search("show") as SearchOutcome.Results).hits
        assertEquals(listOf("Good Show", "Plain http"), hits.map { it.title })
        assertEquals("https://is1.example.com/600.jpg", hits[0].artworkUrl)
        assertEquals("Someone", hits[0].publisher)
        assertTrue(hits[0].isSecure)
        assertFalse("http feeds are allowed and marked not secure", hits[1].isSecure)
        assertNull("artwork that isn't http(s) is dropped", hits[1].artworkUrl)
    }

    @Test
    fun hostileTextIsCleanedAndCapped() = runBlocking {
        // A bell (escaped, as JSON requires) and a right-to-left override, then far too long.
        val title = "Ti\\u0007tle" + Char(0x202E) + "x".repeat(1_000)
        val body = """{"results":[{"collectionName": "$title", "feedUrl": "https://f.example.com/a"}]}"""
        val hit = (search(json(body)).search("x") as SearchOutcome.Results).hits.single()
        assertTrue(hit.title.startsWith("Title"))
        assertEquals(AppleSearch.MAX_TEXT, hit.title.length)
    }

    @Test
    fun atMostFiftyResults() = runBlocking {
        val items = (1..80).joinToString(",") { """{"collectionName": "S$it", "feedUrl": "https://f.example.com/$it"}""" }
        val hits = (search(json("""{"results":[$items]}""")).search("x") as SearchOutcome.Results).hits
        assertEquals(AppleSearch.MAX_RESULTS, hits.size)
    }

    @Test
    fun aBrokenAnswerOrNoNetworkIsAPlainFailure() = runBlocking {
        assertEquals(SearchOutcome.Failed(DiscoverProblem.BadSearchResponse), search(json("<html>not json</html>")).search("x"))
        assertEquals(SearchOutcome.Failed(DiscoverProblem.BadSearchResponse), search(json("""{"results": 5}""")).search("x"))
        val offline = NetError.Network("UnknownHostException: itunes.apple.com")
        assertEquals(SearchOutcome.Failed(DiscoverProblem.Net(offline)), search(FetchResult.Failure(offline)).search("x"))
    }

    @Test
    fun anEmptyTermSendsNothing() = runBlocking {
        assertEquals(SearchOutcome.Results(emptyList()), search(json("{}")).search("   "))
        assertTrue(calls.isEmpty())
    }
}
