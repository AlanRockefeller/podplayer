/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC §12: one field for search terms or a pasted feed URL. */
class DiscoverInputTest {
    @Test
    fun wordsAreASearch() {
        assertEquals(DiscoverInput.Search("the daily"), DiscoverInput.of("  the daily "))
        assertEquals(DiscoverInput.Search("re:Invent"), DiscoverInput.of("re:Invent"))
        assertEquals(DiscoverInput.Search("99.9"), DiscoverInput.of("99.9"))
        assertEquals(DiscoverInput.Search("javascript:alert(1)"), DiscoverInput.of("javascript:alert(1)"))
    }

    @Test
    fun anAddressIsAFeedUrl() {
        assertEquals(DiscoverInput.FeedUrl("https://feeds.example.com/x.xml?token=1"), DiscoverInput.of(" https://feeds.example.com/x.xml?token=1\n"))
        assertEquals(DiscoverInput.FeedUrl("http://192.168.1.5:8080/feed.xml"), DiscoverInput.of("http://192.168.1.5:8080/feed.xml"))
        // Any scheme with "//" is meant as an address; the URL policy refuses what isn't http(s), with a reason.
        assertEquals(DiscoverInput.FeedUrl("feed://example.com/rss"), DiscoverInput.of("feed://example.com/rss"))
    }

    @Test
    fun aHostWithoutASchemeGetsHttps() {
        assertEquals(DiscoverInput.FeedUrl("https://feeds.example.com/podcast.rss"), DiscoverInput.of("feeds.example.com/podcast.rss"))
        assertEquals(DiscoverInput.FeedUrl("https://example.org"), DiscoverInput.of("example.org"))
    }

    @Test
    fun nothingIsNothing() {
        assertEquals(DiscoverInput.Empty, DiscoverInput.of("   "))
    }

    @Test
    fun searchTermsAreCapped() {
        val long = "ab ".repeat(500)
        val term = (DiscoverInput.of(long) as DiscoverInput.Search).term
        assertEquals(DiscoverInput.MAX_TERM, term.length)
        assertEquals(long.take(DiscoverInput.MAX_TERM), term)
    }
}
