/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.settings

import android.app.UiModeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.data.settings.ThemeMode

class NightModeMappingTest {
    @Test
    fun darkAndLightIgnoreTheSystem() {
        assertTrue(ThemeMode.DARK.isDark(systemIsDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemIsDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemIsDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemIsDark = false))
    }

    @Test
    fun useSystemFollowsTheSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemIsDark = false))
    }

    @Test
    fun applicationNightModeMatchesTheChoice() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.DARK.applicationNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.LIGHT.applicationNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.SYSTEM.applicationNightMode())
    }
}
