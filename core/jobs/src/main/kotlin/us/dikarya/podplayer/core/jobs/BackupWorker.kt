/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import us.dikarya.podplayer.core.data.backup.BackupManager

enum class DailyBackupOutcome { Done, NotDue, Failed }

/** What the worker needs from the data layer. */
fun interface DailyBackup {
    suspend fun runIfDue(): DailyBackupOutcome
}

/** The daily backup through [BackupManager.backupIfDue]. */
fun BackupManager.asDailyBackup() = DailyBackup {
    val report = backupIfDue()
    when {
        report == null -> DailyBackupOutcome.NotDue
        report.problem == null -> DailyBackupOutcome.Done
        else -> DailyBackupOutcome.Failed
    }
}

/**
 * The automatic daily backup (SPEC §7). Periodic unique work: every 24 hours, when the battery and storage
 * aren't low. The backup itself decides whether one is due, so an early or repeated run does nothing.
 */
class BackupWorker(context: Context, params: WorkerParameters, private val backups: DailyBackup) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        when (backups.runIfDue()) {
            DailyBackupOutcome.Done, DailyBackupOutcome.NotDue -> Result.success()
            DailyBackupOutcome.Failed -> Result.retry()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // BackupManager logs its own failures; try again later.
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "daily-backup"

        /** Safe to call on every start: an existing schedule is kept. */
        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
