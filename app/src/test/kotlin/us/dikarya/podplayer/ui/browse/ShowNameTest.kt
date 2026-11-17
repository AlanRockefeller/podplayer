/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A show without a title (never loaded yet) is named by its feed's host, not "Untitled show" (owner, 2026-11-16). */
class ShowNameTest {
    @Test
    fun aTitleWins() = assertEquals("The Moth", showName("The Moth", "http://feeds.feedburner.com/themothpodcast"))

    @Test
    fun noTitleFallsBackToTheHostOnly() {
        // Only the host: the path and query of a private feed can carry its token.
        assertEquals("feeds.example.com", showName(null, "https://user:pw@feeds.example.com/p/secret-token?key=abc#x"))
        assertEquals("feeds.example.com", showName("  ", "https://feeds.example.com/rss"))
    }

    @Test
    fun aBrokenAddressGivesNothing() {
        assertNull(showName(null, "not a url"))
        assertNull(showName(null, ""))
    }
}
