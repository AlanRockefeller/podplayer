/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.model.InvariantHandler
import us.dikarya.podplayer.core.model.LoggingInvariantHandler
import us.dikarya.podplayer.ui.shell.DebugScreen

/**
 * Release builds: no StrictMode; invariant violations go to the (redacted) app log and the app keeps going.
 * No Developer screen (SPEC §12): its code and strings exist only in the debug source set.
 */
internal object BuildTypeSetup {
    fun install() = Unit

    fun invariantHandler(log: AppLog): InvariantHandler = LoggingInvariantHandler { log.w("Invariant", it) }

    val debugScreen: DebugScreen? = null
}
