/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.shell

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/**
 * A screen that exists in debug builds only (SPEC §12: the Developer screen), reached from the top bar.
 * Main code knows only this shape; the screen itself lives in the `debug` source set, and release builds
 * provide none (`BuildTypeSetup.debugScreen` is null there), so nothing in a release build can reach it.
 */
class DebugScreen(@param:StringRes val title: Int, val content: @Composable () -> Unit)
