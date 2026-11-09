/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.logs.ExitReasons
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.core.jobs.BackupWorker
import us.dikarya.podplayer.core.jobs.PodplayerWorkerFactory
import us.dikarya.podplayer.core.jobs.asDailyBackup
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate

/**
 * Manual dependency injection: the app's long-lived objects, built once in [PodplayerApp].
 * Building it does no disk or network work, so it is safe on the main thread; [start] begins that work.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Android creates these folders on first use, which is disk work: first touched off the main thread. */
    val files: DataFiles by lazy { DataFiles.from(appContext) }

    /** The rolling, redacted app log (SPEC §13). */
    val log = AppLog(dir = { files.logsDir })

    /** Database, repositories, settings, backups (`:core:data`). The database opens in the background. */
    val data = DataLayer(appContext, appScope, BuildConfig.VERSION_NAME, log, filesProvider = { files })

    val settings: AppSettings get() = data.settings

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

    val workerFactory = PodplayerWorkerFactory { data.backups.asDailyBackup() }

    fun start() {
        data.start()
        appScope.launch(Dispatchers.IO) {
            log.i("App", "Started version ${BuildConfig.VERSION_NAME}")
            data.device.setLastExitSeenAt(ExitReasons.record(appContext, log, data.device.lastExitSeenAt()))
            BackupWorker.schedule(WorkManager.getInstance(appContext))
        }
    }
}
