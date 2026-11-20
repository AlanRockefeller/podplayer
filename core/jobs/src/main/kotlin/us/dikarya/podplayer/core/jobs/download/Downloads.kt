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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Auto-downloads' own network rule (SPEC §9), separate from [wifiOnly]. */
    private val autoWifiOnly: suspend () -> Boolean,
    private val runner: DownloadRunner,
    private val paths: MediaPaths,
    private val log: AppLog,
) {
    /**
     * Queues a download. An owner-started one waits for Wi-Fi (an unmetered network) when the Wi-Fi-only setting
     * is on, which is the default, and runs in the foreground. An [auto] one (SPEC §9: the owner didn't start it)
     * follows the auto-download network setting and runs as background work. The owner asking for an episode
     * whose auto-download is still waiting turns it into the owner's download, same work id. Returns false if
     * there is nothing to download.
     */
    suspend fun enqueue(episodeId: Long, auto: Boolean = false): Boolean {
        val episode = repositories()?.episodes?.get(episodeId) ?: return false
        if (!runner.queue(episodeId)) return false
        if (!auto) {
            val waitingAuto = waitingWork(episodeId)?.takeIf { TAG_AUTO in it.tags }
            if (waitingAuto != null) {
                val result = withContext(Dispatchers.IO) { workManager().updateWork(request(episode, wifiOnly(), waitingAuto.id, auto = false)).get() }
                if (result != WorkManager.UpdateResult.NOT_APPLIED) return true
            }
        }
        val network = if (auto) autoWifiOnly() else wifiOnly()
        workManager().enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request(episode, network, id = null, auto = auto))
        return true
    }

    private suspend fun waitingWork(episodeId: Long): WorkInfo? =
        withContext(Dispatchers.IO) { workManager().getWorkInfosForUniqueWork(workName(episodeId)).get() }
            .firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    /**
     * Moves every download that is still waiting to the current network settings (the owner changed one, or a
     * restore did): owner downloads to the Wi-Fi-only setting, auto-downloads to their own. Each keeps its
     * WorkManager id and its record, so nothing restarts or loses its place. A download already running finishes
     * on the network it has; the new rule applies if it has to run again. Returns how many waiting downloads were
     * updated.
     *
     * One at a time, each reading the settings once it holds the lock: every setting change is written before
     * its apply starts, so the last apply to run always sees the newest settings, and a slower, older one (the
     * one at startup) can't overwrite them with what it read earlier.
     */
    suspend fun applyNetworkSetting(): Int = applying.withLock { applyNetworkSettingNow() }

    private val applying = Mutex()

    private suspend fun applyNetworkSettingNow(): Int {
        val repos = repositories() ?: return 0
        val wifiOnly = wifiOnly()
        val autoWifiOnly = autoWifiOnly()
        val waiting = repos.media.observeAll().first().filter { it.state == DownloadState.Queued || it.state == DownloadState.Downloading }
        var updated = 0
        var updatedAuto = 0
        for (file in waiting) {
            val episode = repos.episodes.get(file.episodeId) ?: continue
            val info = waitingWork(file.episodeId) ?: continue
            val auto = TAG_AUTO in info.tags
            val request = request(episode, if (auto) autoWifiOnly else wifiOnly, info.id, auto)
            val result = withContext(Dispatchers.IO) { workManager().updateWork(request).get() }
            if (result != WorkManager.UpdateResult.NOT_APPLIED) if (auto) updatedAuto++ else updated++
        }
        if (updated > 0) log.i(TAG, networkSettingMessage(updated, wifiOnly))
        if (updatedAuto > 0) log.i(TAG, "Auto-downloads: " + networkSettingMessage(updatedAuto, autoWifiOnly))
        return updated + updatedAuto
    }

    private fun request(episode: Episode, wifiOnly: Boolean, id: UUID?, auto: Boolean): OneTimeWorkRequest {
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .apply { if (id != null) setId(id) }
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_EPISODE to episode.id,
                    DownloadWorker.KEY_TITLE to DownloadNotifications.clean(episode.title),
                    DownloadWorker.KEY_AUTO to auto,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag(feedTag(episode.feedId))
            .apply { if (auto) addTag(TAG_AUTO) }
            .build()
    }

    /** Stops a download; the bytes so far are kept, and downloading again resumes. */
    /**
     * Stops a download. A running one records the stop through its worker; one that hadn't started yet is
     * cleared here, or its record would stay "Queued" with nothing to run it.
     */
    suspend fun cancel(episodeId: Long) {
        workManager().cancelUniqueWork(workName(episodeId))
        runner.stoppedBeforeStart(episodeId)
    }

    /** Stops the download if it runs, then removes the file and its record. */
    suspend fun delete(episodeId: Long) {
        workManager().cancelUniqueWork(workName(episodeId))
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
        /** The app-log line after a Wi-Fi-only change moved [count] waiting downloads. */
        fun networkSettingMessage(count: Int, wifiOnly: Boolean): String = if (count == 1) {
            "1 waiting download now ${if (wifiOnly) "waits for Wi-Fi" else "uses any network"}"
        } else {
            "$count waiting downloads now ${if (wifiOnly) "wait for Wi-Fi" else "use any network"}"
        }

        const val TAG = "Downloads"
        const val TAG_ALL = "download"

        /** Auto-downloads (SPEC §9): background work on their own network rule. */
        const val TAG_AUTO = "download-auto"
        const val BACKOFF_SECONDS = 60L

        fun workName(episodeId: Long) = "download-$episodeId"

        fun feedTag(feedId: Long) = "download-feed-$feedId"
    }
}
