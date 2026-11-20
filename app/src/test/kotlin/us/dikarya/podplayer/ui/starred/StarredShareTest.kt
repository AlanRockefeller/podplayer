/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.starred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import us.dikarya.podplayer.core.model.Enclosure
import us.dikarya.podplayer.core.model.Episode

/** SPEC §12 "Share starred": titles and the feed's item links, never the enclosure URL (it can carry tokens). */
class StarredShareTest {
    private fun ep(id: Long, title: String?, link: String?) = Episode(
        id = id, feedId = 1, guid = null, title = title, publishedAt = 0,
        enclosure = Enclosure("https://cdn.example.com/$id.mp3?token=ENCLOSURESECRET", "audio/mpeg", null),
        durationMs = null, notes = null, imageUrl = null, link = link, season = null, number = null, chaptersUrl = null,
        inFeed = true, starredAt = 1,
    )

    @Test
    fun titlesShowsAndItemLinks() {
        val text = StarredShare.text(
            heading = "Starred episodes",
            untitled = "Untitled episode",
            episodes = listOf(
                ep(1, "Black holes", "https://show.example.com/ep/1") to "Science Hour",
                ep(2, null, null) to null,
            ),
        )
        assertEquals(
            "Starred episodes\n\nBlack holes — Science Hour\nhttps://show.example.com/ep/1\n\nUntitled episode\n",
            text,
        )
    }

    @Test
    fun noEnclosureUrlsAndNoUnsafeOrPrivateLinks() {
        val text = StarredShare.text(
            heading = "Starred",
            untitled = "Untitled",
            episodes = listOf(
                ep(1, "One", "https://cdn.example.com/1.mp3?token=ENCLOSURESECRET") to "S",
                ep(2, "Two", "javascript:alert(1)") to "S",
                ep(3, "Three", "https://alice:hunter2@show.example.com/3") to "S",
                ep(4, "Four${Char(7)}${Char(0x202E)}", "mailto:host@example.com") to "S${Char(0)}",
            ),
        )
        assertFalse(text, "ENCLOSURESECRET" in text)
        assertFalse("an item link that is the enclosure is left out", "cdn.example.com" in text)
        assertFalse("javascript" in text)
        assertFalse("mailto" in text)
        assertFalse("user names and passwords are removed", "hunter2" in text || "alice" in text)
        assertEquals(true, "https://show.example.com/3" in text)
        assertEquals(true, "Four — S\n" in text)
    }
}
