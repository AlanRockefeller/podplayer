/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.cleanup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** What the worker runs: clean-up, then an auto-download pass for "keep starred downloaded". */
fun interface Housekeeping {
    suspend fun run()
}

/**
 * SPEC §9: clean-up runs once a day and soon after playback marks an episode played. Both are unique work with
 * KEEP, so repeated requests add nothing. No network needed.
 */
class CleanUpWorker(context: Context, params: WorkerParameters, private val housekeeping: Housekeeping) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        housekeeping.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Clean-up logs its own failures; the daily run tries again.
        Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "clean-up-daily"
        const val SOON_NAME = "clean-up-soon"

        /** Safe on every start: an existing schedule is kept. */
        fun schedulePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CleanUpWorker>(24, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** After an episode was marked played. */
        fun runSoon(workManager: WorkManager) {
            workManager.enqueueUniqueWork(SOON_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CleanUpWorker>().build())
        }
    }
}
