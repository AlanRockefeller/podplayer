/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTokensTest {
    private val themes = mapOf("dark" to DarkTokens, "light" to LightTokens)

    private fun channel(value: Float): Double =
        if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertAa(theme: String, name: String, fg: Color, bg: Color, bgName: String) {
        val ratio = contrast(fg, bg)
        assertTrue("$theme $name on $bgName is %.2f:1, below 4.5:1".format(ratio), ratio >= 4.5)
    }

    @Test
    fun textAndAccentTokensMeetWcagAaOnEverySurface() {
        for ((theme, t) in themes) {
            val surfaces = mapOf("background" to t.background, "surface" to t.surface, "surfaceElevated" to t.surfaceElevated)
            val foregrounds = mapOf(
                "textPrimary" to t.textPrimary,
                "textSecondary" to t.textSecondary,
                "textQuiet" to t.textQuiet,
                "accent" to t.accent,
                "accentSecondary" to t.accentSecondary,
                "error" to t.error,
                "warning" to t.warning,
            )
            for ((fgName, fg) in foregrounds) {
                for ((bgName, bg) in surfaces) assertAa(theme, fgName, fg, bg, bgName)
            }
        }
    }

    @Test
    fun contentOnAccentAndErrorMeetsWcagAa() {
        for ((theme, t) in themes) {
            assertAa(theme, "onAccent", t.onAccent, t.accent, "accent")
            assertAa(theme, "onAccentSecondary", t.onAccent, t.accentSecondary, "accentSecondary")
            assertAa(theme, "onError", t.onError, t.error, "error")
        }
    }

    @Test
    fun darkTokensMatchTheSpec() {
        val spec = listOf(
            0xFF0D1117, 0xFF151B23, 0xFF202A36, 0xFFF4F7FB, 0xFFB8C5D3, 0xFF8797A8,
            0xFF73B8FF, 0xFF79D2C1, 0xFF303D4B, 0xFFFF9B9B, 0xFFF4C77F,
        )
        assertEquals(spec.map { it.toInt() }, DarkTokens.specOrder().map { it.toArgb() })
    }

    @Test
    fun lightTokensMatchTheSpecExceptTheAdjustedQuietText() {
        // SPEC §12 light textQuiet #617385 is 4.26:1 on surfaceElevated; #5C6E80 is the AA fix.
        val spec = listOf(
            0xFFF7F9FC, 0xFFFFFFFF, 0xFFEBF0F6, 0xFF14202D, 0xFF48596B, 0xFF5C6E80,
            0xFF176DB5, 0xFF176F64, 0xFFD5DFE9, 0xFFB42318, 0xFF885500,
        )
        assertEquals(spec.map { it.toInt() }, LightTokens.specOrder().map { it.toArgb() })
    }

    @Test
    fun windowBackgroundsMatchTheBackgroundTokens() {
        // The starting window is drawn from XML before any Kotlin runs; it must not drift from the tokens.
        val colors = File("src/main/res/values/colors.xml").readText()
        fun xmlColor(name: String): Int {
            val hex = Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""").find(colors)
                ?.groupValues?.get(1) ?: error("missing color $name")
            return (0xFF000000 or hex.toLong(16)).toInt()
        }
        assertEquals(DarkTokens.background.toArgb(), xmlColor("window_background_dark"))
        assertEquals(LightTokens.background.toArgb(), xmlColor("window_background_light"))
    }

    private fun PodplayerColors.specOrder() = listOf(
        background, surface, surfaceElevated, textPrimary, textSecondary, textQuiet,
        accent, accentSecondary, divider, error, warning,
    )
}
