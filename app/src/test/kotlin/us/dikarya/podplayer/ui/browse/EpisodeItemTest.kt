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
import us.dikarya.podplayer.core.model.PlaybackRecord

/** SPEC §10: 98% counts as played, in the UI's progress too. */
class EpisodeItemTest {
    private fun item(positionMs: Long) = EpisodeItem(
        episode = Episode(
            id = 1, feedId = 1, guid = null, title = "T", publishedAt = 0, enclosure = Enclosure("https://e.example.com/1.mp3", null, null),
            durationMs = 100_000, notes = null, imageUrl = null, link = null, season = null, number = null, chaptersUrl = null, inFeed = true,
        ),
        showTitle = null, imageUrl = null, approvedHost = null,
        record = PlaybackRecord(1, positionMs, 100_000, played = false, updatedAt = 0),
        download = null, queued = false, loaded = false, playing = false,
    )

    @Test
    fun progressStopsAtNinetyEightPercent() {
        assertEquals(0.979f, item(97_900).progress!!, 0.0001f)
        assertNull(item(98_000).progress)
        assertNull(item(0).progress)
    }
}
