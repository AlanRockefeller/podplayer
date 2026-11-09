/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.media.MediaPaths
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.jobs.DownloadNotifications

/**
 * What the app calls to download, stop and delete episodes (SPEC §9). Each episode's download is unique
 * WorkManager work: asking again while it waits or runs changes nothing.
 */
class Downloads(
    private val workManager: () -> WorkManager,
    private val repositories: suspend () -> Repositories?,
    private val wifiOnly: suspend () -> Boolean,
    private val runner: DownloadRunner,
    private val paths: MediaPaths,
    private val log: AppLog,
) {
    /**
     * Queues an owner-started download. It waits for Wi-Fi (an unmetered network) when the Wi-Fi-only setting
     * is on, which is the default. Returns false if there is nothing to download.
     */
    suspend fun enqueue(episodeId: Long): Boolean {
        val episode = repositories()?.episodes?.get(episodeId) ?: return false
        if (!runner.queue(episodeId)) return false
        val network = if (wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_EPISODE to episodeId,
                    DownloadWorker.KEY_TITLE to DownloadNotifications.clean(episode.title),
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag(feedTag(episode.feedId))
            .build()
        workManager().enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request)
        return true
    }

    /** Stops a download; the bytes so far are kept, and downloading again resumes. */
    fun cancel(episodeId: Long) {
        workManager().cancelUniqueWork(workName(episodeId))
    }

    /** Stops the download if it runs, then removes the file and its record. */
    suspend fun delete(episodeId: Long) {
        cancel(episodeId)
        runner.delete(episodeId)
    }

    /**
     * Unsubscribes: stops the feed's downloads, deletes its media folder, then removes the feed, whose rows
     * cascade (PROGRESS "Later", S5).
     */
    suspend fun removeFeed(feedId: Long) {
        val repos = repositories() ?: return
        workManager().cancelAllWorkByTag(feedTag(feedId))
        for (file in repos.media.forFeed(feedId)) runner.delete(file.episodeId)
        withContext(Dispatchers.IO) {
            paths.resolve("${MediaPaths.MEDIA_DIR}/$feedId")?.deleteRecursively()
        }
        repos.feeds.unsubscribe(feedId)
        log.i(TAG, "Feed $feedId removed with its downloads")
    }

    companion object {
        const val TAG = "Downloads"
        const val TAG_ALL = "download"
        const val BACKOFF_SECONDS = 60L

        fun workName(episodeId: Long) = "download-$episodeId"

        fun feedTag(feedId: Long) = "download-feed-$feedId"
    }
}
