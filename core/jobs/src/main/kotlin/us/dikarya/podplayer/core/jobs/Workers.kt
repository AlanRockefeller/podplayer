/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import us.dikarya.podplayer.core.jobs.cleanup.CleanUpWorker
import us.dikarya.podplayer.core.jobs.cleanup.Housekeeping
import us.dikarya.podplayer.core.jobs.download.DownloadTask
import us.dikarya.podplayer.core.jobs.download.DownloadWorker
import us.dikarya.podplayer.core.jobs.refresh.Refresher
import us.dikarya.podplayer.core.jobs.refresh.RefreshWorker

/**
 * Builds this app's workers with their dependencies (manual DI, no framework). Returns null for anything else,
 * which lets WorkManager fall back to its default.
 */
class PodplayerWorkerFactory(
    private val backups: () -> DailyBackup,
    private val refresher: () -> Refresher,
    private val downloads: () -> DownloadTask,
    private val notifications: () -> DownloadNotifications,
    private val housekeeping: () -> Housekeeping = { Housekeeping {} },
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            BackupWorker::class.java.name -> BackupWorker(appContext, workerParameters, backups())
            RefreshWorker::class.java.name -> RefreshWorker(appContext, workerParameters, refresher())
            DownloadWorker::class.java.name -> DownloadWorker(appContext, workerParameters, downloads(), notifications())
            CleanUpWorker::class.java.name -> CleanUpWorker(appContext, workerParameters, housekeeping())
            else -> null
        }
}
