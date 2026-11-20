/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.annotation.DrawableRes

/**
 * The skip intervals the owner can choose in Settings, and their numbered notification icons. Media3 has numbered
 * icons for 5, 10, 15 and 30 seconds; the other values use drawables of our own. Settings offers exactly these
 * lists, so every choice gets a number on the lock screen (tested).
 */
object SkipIntervals {
    val BACK_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60)
    val FORWARD_SECONDS = listOf(10, 15, 20, 30, 45, 60, 90, 120)

    /** Our own numbered icon for skipping back [seconds], or null where Media3's own icon has the number. */
    @DrawableRes
    fun backIcon(seconds: Int): Int? = when (seconds) {
        20 -> R.drawable.ic_skip_back_20
        45 -> R.drawable.ic_skip_back_45
        60 -> R.drawable.ic_skip_back_60
        else -> null
    }

    /** Our own numbered icon for skipping forward [seconds], or null where Media3's own icon has the number. */
    @DrawableRes
    fun forwardIcon(seconds: Int): Int? = when (seconds) {
        20 -> R.drawable.ic_skip_forward_20
        45 -> R.drawable.ic_skip_forward_45
        60 -> R.drawable.ic_skip_forward_60
        90 -> R.drawable.ic_skip_forward_90
        120 -> R.drawable.ic_skip_forward_120
        else -> null
    }
}
