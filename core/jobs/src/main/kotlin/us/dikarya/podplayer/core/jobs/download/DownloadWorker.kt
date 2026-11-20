/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.jobs.DownloadNotifications

/**
 * One episode's download as WorkManager work (SPEC §9).
 *
 * Owner-started downloads run as a foreground service of type `dataSync` with a progress notification. If
 * Android refuses the foreground service (the app is in the background, or on Android 15+ the daily
 * `dataSync` allowance is used up), the download carries on as ordinary background work, which Android may
 * stop and WorkManager resumes later, from the bytes already on disk.
 *
 * Android 15+ `onTimeout`: WorkManager's foreground service handles the system callback and stops this worker
 * with [WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT]. The runner then stops writing, saves the partial
 * file as paused and logs why, and WorkManager reschedules the work.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val task: DownloadTask,
    private val notifications: DownloadNotifications,
) : CoroutineWorker(context, params) {
    private val episodeId: Long get() = inputData.getLong(KEY_EPISODE, -1)
    private val title: String? get() = inputData.getString(KEY_TITLE)

    /** SPEC §9: auto-downloads run as background work; the owner didn't start them. */
    private val foreground: Boolean get() = !inputData.getBoolean(KEY_AUTO, false)

    override suspend fun doWork(): Result {
        val id = episodeId
        if (id <= 0) return Result.failure()
        if (foreground) goForeground(notifications.foregroundInfo(this.id, id, title, 0, null))
        val progress = AtomicReference<Pair<Long, Long?>?>(null)
        val outcome = try {
            coroutineScope {
                val updater = launch {
                    var shown: Pair<Long, Long?>? = null
                    while (true) {
                        delay(PROGRESS_UPDATE_MS)
                        val now = progress.get()
                        if (now != null && now != shown) {
                            shown = now
                            setProgress(workDataOf(KEY_BYTES to now.first, KEY_TOTAL to (now.second ?: -1L)))
                            if (foreground) goForeground(notifications.foregroundInfo(this@DownloadWorker.id, id, title, now.first, now.second))
                        }
                    }
                }
                try {
                    task.run(id, { bytes, total -> progress.set(bytes to total) }, ::currentStopCause)
                } finally {
                    updater.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unexpected; the runner logs its own failures. Treat like a transient error.
            DownloadOutcome.Retry("unexpected error")
        }
        return when (outcome) {
            DownloadOutcome.Completed, DownloadOutcome.AlreadyComplete, DownloadOutcome.Gone -> Result.success()
            is DownloadOutcome.Failed -> Result.failure()
            is DownloadOutcome.Retry -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                task.gaveUp(id, runAttemptCount + 1)
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    /** Used for expedited work on Android 11 and older; downloads aren't expedited, but this keeps it safe. */
    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(id, episodeId, title, 0, null)

    private suspend fun goForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
            // Not allowed now (for example the app went to the background); continue as background work.
        }
    }

    private fun currentStopCause(): StopCause =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopCauseFor(stopReason) else StopCause.System

    companion object {
        const val KEY_EPISODE = "episode"
        const val KEY_TITLE = "title"
        const val KEY_AUTO = "auto"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_UPDATE_MS = 1_000L

        fun stopCauseFor(reason: Int): StopCause = when (reason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> StopCause.ForegroundTimeout
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> StopCause.Owner
            else -> StopCause.System
        }
    }
}
