/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC §10: a global default speed and a per-show override, edited separately in the speed sheet. */
class SpeedsTest {
    @Test
    fun theSpeedSheetMarksTheSpeedOfTheScopeBeingEdited() {
        // The show plays at its own 1.5x; the default for every show is 1.0x.
        assertEquals("for all: the default, not the speed playing", 1.0f, selectedSpeed(forShow = false, showSpeed = 1.5f, globalSpeed = 1.0f), 0f)
        assertEquals(1.5f, selectedSpeed(forShow = true, showSpeed = 1.5f, globalSpeed = 1.0f), 0f)
        assertEquals("a show without its own speed uses the default", 1.2f, selectedSpeed(forShow = true, showSpeed = null, globalSpeed = 1.2f), 0f)
    }
}
