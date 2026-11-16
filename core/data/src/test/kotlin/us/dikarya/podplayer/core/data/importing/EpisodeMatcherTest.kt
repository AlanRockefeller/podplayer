/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.importing

import org.junit.Assert.assertEquals
import org.junit.Test
import us.dikarya.podplayer.core.model.Enclosure
import us.dikarya.podplayer.core.model.Episode

/** SPEC §12: imported episodes are matched by show, then episode title and date. */
class EpisodeMatcherTest {
    private val day = 24L * 3600 * 1000
    private val base = 1_790_000_000_000L

    private fun ep(id: Long, title: String?, at: Long) = Episode(
        id = id, feedId = 1, guid = "g$id", title = title, publishedAt = at,
        enclosure = Enclosure("https://cdn.example.com/$id.mp3", null, null), durationMs = null, notes = null,
        imageUrl = null, link = null, season = null, number = null, chaptersUrl = null, inFeed = true,
    )

    private fun entry(title: String?, at: Long? = null) = ImportEpisode(title = title, publishedAt = at)

    @Test
    fun titlesMatchIgnoringCasePunctuationAndSpacing() {
        val library = listOf(ep(1, "Episode 12: Black Holes!", base), ep(2, "Quarks", base - day))
        val matches = EpisodeMatcher.matchAll(listOf(entry("episode 12 -  black holes"), entry("QUARKS")), library)
        assertEquals(listOf(1L, 2L), matches.map { it?.id })
    }

    @Test
    fun aRepeatedTitleIsSettledByTheNearestDateWithinTwoDays() {
        val library = listOf(ep(1, "Mailbag", base), ep(2, "Mailbag", base - 7 * day), ep(3, "Mailbag", base - 14 * day))
        assertEquals(2L, EpisodeMatcher.matchAll(listOf(entry("Mailbag", base - 7 * day + 3_600_000)), library).single()?.id)
        assertEquals("ambiguous without a close date", null, EpisodeMatcher.matchAll(listOf(entry("Mailbag", base - 4 * day)), library).single())
        assertEquals("ambiguous without any date", null, EpisodeMatcher.matchAll(listOf(entry("Mailbag")), library).single())
    }

    @Test
    fun aUniqueTitleMatchesEvenWhenTheDateMovedARepost() {
        val library = listOf(ep(1, "The Interview", base))
        assertEquals(1L, EpisodeMatcher.matchAll(listOf(entry("The Interview", base - 30 * day)), library).single()?.id)
    }

    @Test
    fun aDateAloneNeverMatches() {
        val library = listOf(ep(1, "Something", base))
        assertEquals(null, EpisodeMatcher.matchAll(listOf(entry("Something else", base)), library).single())
        assertEquals(null, EpisodeMatcher.matchAll(listOf(entry(null, base)), library).single())
    }

    @Test
    fun eachLibraryEpisodeIsUsedOnce() {
        val library = listOf(ep(1, "Mailbag", base), ep(2, "Mailbag", base - 7 * day))
        val matches = EpisodeMatcher.matchAll(listOf(entry("Mailbag", base), entry("Mailbag", base + 3_600_000), entry("Mailbag", base - 7 * day)), library)
        assertEquals(listOf(1L, null, 2L), matches.map { it?.id })
    }
}
