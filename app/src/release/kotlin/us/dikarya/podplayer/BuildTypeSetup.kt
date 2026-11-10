/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.util.Log
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

    /**
     * Violations go to the app log, or to logcat while there is no log yet (before `AppGraph` exists, and in
     * the sandbox process). Invariant messages carry no URLs or other secrets (`assertInvariant`).
     */
    fun invariantHandler(log: () -> AppLog?): InvariantHandler = LoggingInvariantHandler { message ->
        val appLog = log()
        if (appLog != null) appLog.w("Invariant", message) else Log.w("Podplayer", message)
    }

    val debugScreen: DebugScreen? = null
}
