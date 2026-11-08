/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalPodplayerColors = staticCompositionLocalOf { DarkTokens }

/** Tokens without a Material 3 role (textQuiet, warning, ...) are read from here. */
object PodplayerTheme {
    val colors: PodplayerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPodplayerColors.current
}

/** Maps the semantic tokens onto Material 3 roles, so stock components use them too. */
fun PodplayerColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surfaceElevated,
        onPrimaryContainer = textPrimary,
        secondary = accentSecondary,
        onSecondary = onAccent,
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = textPrimary,
        tertiary = accentSecondary,
        onTertiary = onAccent,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        surfaceBright = surfaceElevated,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceElevated,
        inverseSurface = textPrimary,
        inverseOnSurface = background,
        outline = textQuiet,
        outlineVariant = divider,
        error = error,
        onError = onError,
        errorContainer = surfaceElevated,
        onErrorContainer = error,
        scrim = background,
    )
}

@Composable
fun PodplayerTheme(colors: PodplayerColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPodplayerColors provides colors) {
        MaterialTheme(colorScheme = colors.toColorScheme(), content = content)
    }
}
