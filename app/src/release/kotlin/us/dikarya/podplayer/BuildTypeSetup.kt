/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.util.Log
import us.dikarya.podplayer.core.model.InvariantHandler
import us.dikarya.podplayer.core.model.LoggingInvariantHandler

/** Release builds: no StrictMode; invariant violations are logged and the app keeps going. */
internal object BuildTypeSetup {
    fun install() = Unit

    // M1-S4 replaces logcat with the rolling, redacted log files.
    fun invariantHandler(): InvariantHandler = LoggingInvariantHandler { Log.w("Podplayer", it) }
}
