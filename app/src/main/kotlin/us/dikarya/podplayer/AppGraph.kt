/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.settings.AppSettings
import us.dikarya.podplayer.settings.ThemeMode

/** Manual dependency injection: the app's long-lived objects, built once in [PodplayerApp]. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings = AppSettings.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        File(appContext.filesDir, "datastore/${AppSettings.FILE_NAME}")
    }

    /**
     * The saved theme, or null until it has been read. Reading starts here, at process start,
     * so it is usually ready before the first frame (see MainActivity).
     */
    val themeMode: StateFlow<ThemeMode?> = settings.themeMode.stateIn(appScope, SharingStarted.Eagerly, null)

    /**
     * The one gated HTTP client (CLAUDE.md, SPEC §3). Pass this, or a `Call.Factory` from it, to
     * everything that needs the network, including ExoPlayer. Built on first use.
     */
    val netGate: NetGate by lazy { NetGate(NetConfig(appVersion = BuildConfig.VERSION_NAME)) }
}
