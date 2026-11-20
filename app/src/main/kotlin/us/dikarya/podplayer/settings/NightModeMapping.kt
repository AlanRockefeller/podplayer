/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.settings

import android.app.UiModeManager
import us.dikarya.podplayer.core.data.settings.ThemeMode

/** Whether the app draws dark, given the system's current setting. */
fun ThemeMode.isDark(systemIsDark: Boolean): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemIsDark
}

/**
 * The value for `UiModeManager.setApplicationNightMode` (API 31+). Android remembers it, so the
 * system starting window already uses the right background on the next launch.
 */
fun ThemeMode.applicationNightMode(): Int = when (this) {
    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
}
