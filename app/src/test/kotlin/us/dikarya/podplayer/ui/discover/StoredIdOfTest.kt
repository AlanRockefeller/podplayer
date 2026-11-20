/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import us.dikarya.podplayer.core.feed.ParsedEnclosure
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.model.Enclosure
import us.dikarya.podplayer.core.model.Episode

/** Finding the stored row for a previewed episode, so Play plays the episode the owner tapped (SPEC §12). */
class StoredIdOfTest {
    private fun stored(id: Long, guid: String?, url: String, title: String? = null, at: Long = 0) = Episode(
        id = id, feedId = 1, guid = guid, title = title, publishedAt = at, enclosure = Enclosure(url, "audio/mpeg", null),
        durationMs = null, notes = null, imageUrl = null, link = null, season = null, number = null, chaptersUrl = null,
        inFeed = true,
    )

    private fun parsed(guid: String?, url: String, title: String? = null, at: Long? = null) =
        ParsedEpisode(guid = guid, title = title, publishedAt = at, enclosure = ParsedEnclosure(url, "audio/mpeg", null))

    /** CodeRabbit review, PR #1: episodes without GUIDs sharing one audio address all opened the first row. */
    @Test
    fun episodesSharingAudioAreToldApartByTitleThenDate() {
        val url = "https://cdn.example.com/same.mp3"
        val day = 86_400_000L
        val rows = listOf(stored(1, null, url, "Part one", 10 * day), stored(2, null, url, "Part two", 11 * day), stored(3, null, url, "Part two", 20 * day))
        assertEquals(1L, rows.storedIdOf(parsed(null, url, "Part one", 10 * day)))
        assertEquals(2L, rows.storedIdOf(parsed(null, url, "Part two", 11 * day)))
        assertEquals(3L, rows.storedIdOf(parsed(null, url, "Part two", 20 * day)))
        assertEquals("an unknown title falls back to the nearest date", 2L, rows.storedIdOf(parsed(null, url, "Renamed", 12 * day)))
    }

    /** CodeRabbit CLI review, PR #8: the merge drops "episode a" as a duplicate of "Episode A"; it must open A. */
    @Test
    fun titlesAreComparedTheWayTheMergeComparesThem() {
        val url = "https://cdn.example.com/same.mp3"
        val tenTen = 1_790_000_000_000L
        val rows = listOf(stored(1, null, url, "Episode A", tenTen), stored(2, null, url, "Episode B", tenTen - 11 * 60_000))
        assertEquals(1L, rows.storedIdOf(parsed(null, url, "  episode   a ", tenTen - 10 * 60_000)))
        assertEquals("full-width letters count as the same title", 1L, rows.storedIdOf(parsed(null, url, "Ｅｐｉｓｏｄｅ A", tenTen - 10 * 60_000)))
    }

    /** Codex review, PR #31: a reused GUID picked the first row for every episode sharing it. */
    @Test
    fun aReusedGuidIsToldApartByItsAudio() {
        val rows = listOf(stored(1, "same", "https://cdn.example.com/a.mp3"), stored(2, "same", "https://cdn.example.com/b.mp3"))
        assertEquals(1L, rows.storedIdOf(parsed("same", "https://cdn.example.com/a.mp3")))
        assertEquals(2L, rows.storedIdOf(parsed("same", "https://cdn.example.com/b.mp3")))
    }

    @Test
    fun aGuidOnlyOneRowHasFindsItWhenTheAudioChanged() {
        val rows = listOf(stored(1, "g1", "https://cdn.example.com/old.mp3"), stored(2, "g2", "https://cdn.example.com/2.mp3"))
        assertEquals(1L, rows.storedIdOf(parsed("g1", "https://cdn.example.com/new.mp3")))
    }

    @Test
    fun withoutAGuidTheAudioDecidesAndAnUnknownEpisodeIsNull() {
        val rows = listOf(stored(1, null, "https://cdn.example.com/1.mp3"), stored(2, "same", "https://cdn.example.com/2.mp3"), stored(3, "same", "https://cdn.example.com/3.mp3"))
        assertEquals(1L, rows.storedIdOf(parsed(null, "https://cdn.example.com/1.mp3")))
        assertNull(rows.storedIdOf(parsed(null, "https://cdn.example.com/9.mp3")))
        assertNull("an ambiguous GUID with unknown audio matches nothing", rows.storedIdOf(parsed("same", "https://cdn.example.com/9.mp3")))
    }
}
