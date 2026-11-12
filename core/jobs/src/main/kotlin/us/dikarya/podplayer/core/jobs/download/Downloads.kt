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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.Episode
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
        workManager().enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request(episode, wifiOnly(), id = null))
        return true
    }

    /**
     * Moves every download that is still waiting to the current Wi-Fi-only setting (the owner changed it, or a
     * restore did). Each keeps its WorkManager id and its record, so nothing restarts or loses its place. A
     * download already running finishes on the network it has; the new rule applies if it has to run again.
     * Returns how many waiting downloads were updated.
     */
    suspend fun applyNetworkSetting(): Int {
        val repos = repositories() ?: return 0
        val wifiOnly = wifiOnly()
        val waiting = repos.media.observeAll().first().filter { it.state == DownloadState.Queued || it.state == DownloadState.Downloading }
        var updated = 0
        for (file in waiting) {
            val episode = repos.episodes.get(file.episodeId) ?: continue
            val info = withContext(Dispatchers.IO) { workManager().getWorkInfosForUniqueWork(workName(file.episodeId)).get() }
                .firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } ?: continue
            val result = withContext(Dispatchers.IO) { workManager().updateWork(request(episode, wifiOnly, info.id)).get() }
            if (result != WorkManager.UpdateResult.NOT_APPLIED) updated++
        }
        if (updated > 0) log.i(TAG, "$updated waiting downloads now ${if (wifiOnly) "wait for Wi-Fi" else "use any network"}")
        return updated
    }

    private fun request(episode: Episode, wifiOnly: Boolean, id: UUID?): OneTimeWorkRequest {
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .apply { if (id != null) setId(id) }
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_EPISODE to episode.id,
                    DownloadWorker.KEY_TITLE to DownloadNotifications.clean(episode.title),
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag(feedTag(episode.feedId))
            .build()
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
