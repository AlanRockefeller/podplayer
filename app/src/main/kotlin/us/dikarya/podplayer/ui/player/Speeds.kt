/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import java.util.Locale
import kotlin.math.roundToInt

/** The speeds the owner can pick (SPEC §10: global and per show), within `PlaybackSettings`' range. */
val SPEED_CHOICES = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/** The sleep timer's choices (SPEC §10: 5 to 120 minutes). */
val SLEEP_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

/** "1.0×", "1.25×". */
fun speedText(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    return String.format(Locale.getDefault(), if (hundredths % 10 == 0) "%.1f×" else "%.2f×", speed)
}

/** A position or length as a clock: "4:05", "1:02:03". */
fun clockText(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s) else String.format(Locale.getDefault(), "%d:%02d", m, s)
}
