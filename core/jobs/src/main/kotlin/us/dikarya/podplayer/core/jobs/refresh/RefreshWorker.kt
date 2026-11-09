/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.refresh

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a [Refresher] as WorkManager work (SPEC §8): the periodic refresh of due feeds every 4 hours, and
 * one-time refreshes the owner asks for. All of it is unique work with KEEP, so a second request while one is
 * waiting or running adds nothing; the refresher itself collapses requests that overlap across workers.
 */
class RefreshWorker(context: Context, params: WorkerParameters, private val refresher: Refresher) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val request = when {
            inputData.getBoolean(KEY_ALL, false) -> RefreshRequest.All
            inputData.getLong(KEY_FEED, -1) > 0 -> RefreshRequest.Feed(inputData.getLong(KEY_FEED, -1))
            else -> RefreshRequest.Due
        }
        return try {
            refresher.refresh(request)
            // Per-feed failures are handled by the refresher's backoff, not by retrying the whole run.
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "feed-refresh-periodic"
        const val KEY_ALL = "all"
        const val KEY_FEED = "feed"
        const val INTERVAL_HOURS = 4L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun nowName(feedId: Long?) = if (feedId == null) "feed-refresh-all" else "feed-refresh-$feedId"

        /** Safe on every start: an existing schedule is kept. */
        fun schedulePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Refresh one feed ([feedId]) or all of them, ignoring backoff, as soon as there is a network. */
        fun refreshNow(workManager: WorkManager, feedId: Long?) {
            val data = if (feedId == null) workDataOf(KEY_ALL to true) else workDataOf(KEY_FEED to feedId)
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(network)
                .setInputData(data)
                .build()
            workManager.enqueueUniqueWork(nowName(feedId), ExistingWorkPolicy.KEEP, request)
        }
    }
}
