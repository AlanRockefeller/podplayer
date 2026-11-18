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
import us.dikarya.podplayer.core.data.settings.RefreshSchedule

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
        /** The single 4-hour schedule from before the setting (SPEC §8); cancelled when the new ones are set. */
        const val OLD_PERIODIC_NAME = "feed-refresh-periodic"
        const val PERIODIC_WIFI_NAME = "feed-refresh-periodic-wifi"
        const val PERIODIC_ANY_NAME = "feed-refresh-periodic-any-network"
        const val KEY_ALL = "all"
        const val KEY_FEED = "feed"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun nowName(feedId: Long?) = if (feedId == null) "feed-refresh-all" else "feed-refresh-$feedId"

        /**
         * The background refresh, by [schedule] (owner, 2026-11-18): every [RefreshSchedule.wifiMinutes] on Wi-Fi
         * (an unmetered network) and every [RefreshSchedule.mobileMinutes] on any network, or never on mobile
         * data. On Wi-Fi both run; the refresher runs one refresh at a time and skips feeds backing off, so the
         * hourly one adds little. Safe on every start: an unchanged schedule keeps its work and its timing.
         */
        fun schedulePeriodic(workManager: WorkManager, schedule: RefreshSchedule) {
            workManager.cancelUniqueWork(OLD_PERIODIC_NAME)
            enqueue(workManager, PERIODIC_WIFI_NAME, schedule.wifiMinutes, unmetered)
            val mobile = schedule.mobileMinutes
            if (mobile == null) workManager.cancelUniqueWork(PERIODIC_ANY_NAME) else enqueue(workManager, PERIODIC_ANY_NAME, mobile, network)
        }

        private fun enqueue(workManager: WorkManager, name: String, minutes: Int, constraints: Constraints) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            // UPDATE keeps the work (and when it last ran) and only changes what differs.
            workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
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
