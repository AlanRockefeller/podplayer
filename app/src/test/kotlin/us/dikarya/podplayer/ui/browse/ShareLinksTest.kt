/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import us.dikarya.podplayer.core.model.Enclosure
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.Feed

/**
 * Owner, 2026-11-18: copy or share a link to an episode or a show. Only the web pages the feed names; never the
 * feed's address or the audio file's, which can carry a private feed's token (CLAUDE.md "Feed URLs are secrets").
 */
class ShareLinksTest {
    private val feedUrl = "https://feeds.example.com/private/rss?token=FEEDSECRET"

    private fun feed(link: String?) = Feed(
        id = 1, url = feedUrl, title = "Science Hour", link = link, description = null, author = null, imageUrl = null,
        categories = emptyList(), ownerApprovedHost = null, subscribedAt = 0, lastRefreshSuccessAt = null,
        lastRefreshErrorAt = null, lastRefreshError = null,
    )

    private fun ep(link: String?) = Episode(
        id = 1, feedId = 1, guid = null, title = "Black holes", publishedAt = 0,
        enclosure = Enclosure("https://cdn.example.com/1.mp3?token=ENCLOSURESECRET", "audio/mpeg", null),
        durationMs = null, notes = null, imageUrl = null, link = link, season = null, number = null, chaptersUrl = null,
        inFeed = true,
    )

    @Test
    fun anEpisodeSharesItsOwnPage() {
        val link = ShareLinks.episode(ep("https://show.example.com/ep/1"), feed("https://show.example.com/"))!!
        assertEquals("https://show.example.com/ep/1", link.url)
        assertEquals(false, link.isShowSite)
        assertEquals("Black holes — Science Hour\nhttps://show.example.com/ep/1", ShareLinks.text("Black holes", "Science Hour", link))
    }

    @Test
    fun withoutItsOwnPageAnEpisodeOffersTheShowsSite() {
        val link = ShareLinks.episode(ep(null), feed("https://show.example.com/"))!!
        assertEquals("https://show.example.com/", link.url)
        assertEquals(true, link.isShowSite)
        assertNull(ShareLinks.episode(ep(null), feed(null)))
    }

    /** Codex review, PR #30: the show's website stood in for the episode without being checked against its audio. */
    @Test
    fun aShowSiteThatIsTheEpisodesAudioIsNeverOffered() {
        assertNull(ShareLinks.episode(ep(null), feed("https://cdn.example.com/1.mp3?token=ENCLOSURESECRET")))
        assertNull(ShareLinks.episode(ep(null), feed("https://cdn.example.com/1.mp3")))
    }

    @Test
    fun aShowSharesItsWebsite() {
        assertEquals("https://show.example.com/", ShareLinks.show(feed("https://show.example.com/"))?.url)
        assertEquals("Science Hour\nhttps://show.example.com/", ShareLinks.text(null, "Science Hour", ShareLinks.show(feed("https://show.example.com/"))!!))
        assertNull(ShareLinks.show(feed(null)))
    }

    @Test
    fun neverTheFeedOrTheAudioFileAndOnlyPlainWebLinks() {
        assertNull("the item link is the audio file", ShareLinks.episode(ep("https://cdn.example.com/1.mp3?token=ENCLOSURESECRET"), feed(null)))
        assertNull("the feed names itself as its website", ShareLinks.show(feed("https://feeds.example.com/private/rss?token=FEEDSECRET")))
        assertNull("the feed's address without its query is still the feed", ShareLinks.show(feed("https://feeds.example.com/private/rss")))
        assertNull(ShareLinks.show(feed("javascript:alert(1)")))
        assertNull(ShareLinks.show(feed("mailto:host@example.com")))
        assertNull(ShareLinks.show(feed("ftp://show.example.com/")))
        assertEquals("https://show.example.com/3", ShareLinks.episode(ep("https://alice:hunter2@show.example.com/3"), feed(null))?.url)
        assertEquals("https://show.example.com/", ShareLinks.show(feed("  https://show.example.com/  "))?.url)
    }

    /** CodeRabbit review, PR #1: the same path spelled with percent escapes slipped past the check. */
    @Test
    fun theFeedOrAudioSpelledWithPercentEscapesIsStillLeftOut() {
        assertNull(ShareLinks.show(feed("https://feeds.example.com/%70rivate/rss?token=FEEDSECRET")))
        assertNull(ShareLinks.show(feed("https://feeds.example.com/private/%72%73%73?token=FEEDSECRET")))
        assertNull(ShareLinks.episode(ep("https://cdn.example.com/%31.mp3?token=ENCLOSURESECRET"), feed(null)))
        assertNull(ShareLinks.episode(ep("https://CDN.example.com/1%2emp3?token=ENCLOSURESECRET"), feed(null)))
    }

    @Test
    fun titlesAreCleanedOfControlCharacters() {
        val link = ShareLinks.show(feed("https://show.example.com/"))!!
        assertEquals("Four — S\nhttps://show.example.com/", ShareLinks.text("Four${Char(7)}${Char(0x202E)}", "S${Char(0)}", link))
    }
}
