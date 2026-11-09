/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens (SPEC §12). This package is the only place hex colors may appear;
 * the forbidden-API check enforces it for everything else in `:app`.
 */
@Immutable
data class PodplayerColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textQuiet: Color,
    val accent: Color,
    val accentSecondary: Color,
    val divider: Color,
    val error: Color,
    val warning: Color,
    /** Content drawn on [accent] or [accentSecondary]. */
    val onAccent: Color,
    /** Content drawn on [error]. */
    val onError: Color,
    val isDark: Boolean,
)

private val DarkBackground = Color(0xFF0D1117)
private val LightSurface = Color(0xFFFFFFFF)

val DarkTokens = PodplayerColors(
    background = DarkBackground,
    surface = Color(0xFF151B23),
    surfaceElevated = Color(0xFF202A36),
    textPrimary = Color(0xFFF4F7FB),
    textSecondary = Color(0xFFB8C5D3),
    textQuiet = Color(0xFF8797A8),
    accent = Color(0xFF73B8FF),
    accentSecondary = Color(0xFF79D2C1),
    divider = Color(0xFF303D4B),
    error = Color(0xFFFF9B9B),
    warning = Color(0xFFF4C77F),
    onAccent = DarkBackground,
    onError = DarkBackground,
    isDark = true,
)

val LightTokens = PodplayerColors(
    background = Color(0xFFF7F9FC),
    surface = LightSurface,
    surfaceElevated = Color(0xFFEBF0F6),
    textPrimary = Color(0xFF14202D),
    textSecondary = Color(0xFF48596B),
    // SPEC §12 says #617385, which is 4.26:1 on surfaceElevated; darkened to pass WCAG AA (4.5:1).
    textQuiet = Color(0xFF5C6E80),
    accent = Color(0xFF176DB5),
    accentSecondary = Color(0xFF176F64),
    divider = Color(0xFFD5DFE9),
    error = Color(0xFFB42318),
    warning = Color(0xFF885500),
    onAccent = LightSurface,
    onError = LightSurface,
    isDark = false,
)
