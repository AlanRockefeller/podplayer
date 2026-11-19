/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import androidx.work.WorkManager
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.jobs.cleanup.CleanUp
import us.dikarya.podplayer.core.jobs.cleanup.CleanUpWorker
import us.dikarya.podplayer.core.jobs.cleanup.Housekeeping
import us.dikarya.podplayer.core.jobs.download.AudioProbe
import us.dikarya.podplayer.core.jobs.download.AutoDownloadAfterRefresh
import us.dikarya.podplayer.core.jobs.download.AutoDownloader
import us.dikarya.podplayer.core.jobs.download.DownloadRunner
import us.dikarya.podplayer.core.jobs.download.Downloads
import us.dikarya.podplayer.core.jobs.download.KeyedLocks
import us.dikarya.podplayer.core.jobs.refresh.FeedRefresher
import us.dikarya.podplayer.core.jobs.refresh.RefreshWorker
import us.dikarya.podplayer.core.jobs.refresh.androidFeedParser
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.core.net.download.RangeDownloader

/**
 * The background jobs as `AppGraph` builds them: refresh (SPEC §8), downloads (SPEC §9) and the daily backup.
 * Building it does no work; everything is created on first use.
 */
class Jobs(
    context: Context,
    private val data: DataLayer,
    private val gate: () -> NetGate,
    private val log: AppLog,
    /** Lives as long as the process; refreshes run in it. */
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val locks = KeyedLocks()

    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    val refresher: FeedRefresher by lazy {
        FeedRefresher({ data.awaitRepositories() }, gate(), androidFeedParser(), log, scope)
    }

    private val runner: DownloadRunner by lazy {
        DownloadRunner(
            repositories = { data.awaitRepositories() },
            paths = data.paths,
            storageCap = { data.settings.storageCapBytes.first() },
            engine = RangeDownloader(gate()),
            probe = AudioProbe(),
            log = log,
            locks = locks,
        )
    }

    val downloads: Downloads by lazy {
        Downloads(
            workManager = { workManager },
            repositories = { data.awaitRepositories() },
            wifiOnly = { data.settings.downloadsWifiOnly.first() },
            autoWifiOnly = { data.settings.autoDownloadWifiOnly.first() },
            runner = runner,
            paths = data.paths,
            log = log,
        )
    }

    /** SPEC §9 auto-download; runs after every refresh worker run and with the daily housekeeping. */
    val autoDownloader: AutoDownloader by lazy {
        AutoDownloader(
            repositories = { data.awaitRepositories() },
            enqueue = { downloads.enqueue(it, auto = true) },
            storageCap = { data.settings.storageCapBytes.first() },
            cleanUp = { data.settings.cleanUp.first() },
            device = data.device,
            log = log,
        )
    }

    /** SPEC §9 clean-up of downloaded files. */
    val cleanUp: CleanUp by lazy {
        CleanUp(
            repositories = { data.awaitRepositories() },
            runner = runner,
            settings = { data.settings.cleanUp.first() },
            loadedEpisodeId = { data.device.currentEpisodeId() },
            log = log,
        )
    }

    private val notifications by lazy { DownloadNotifications(appContext) }

    /**
     * Shows kept aside to play from a preview (SPEC §12) leave once nothing of them is queued, downloaded, starred,
     * started or loaded. Their downloads, if any, keep them, so no file is ever left without its episode.
     */
    private suspend fun removeUnusedKeptAside() {
        val repos = data.awaitRepositories() ?: return
        val removed = repos.feeds.removeUnusedKeptAside(data.device.currentEpisodeId())
        if (removed > 0) log.i(TAG, "Removed $removed shows kept aside from a preview")
    }

    val workerFactory = PodplayerWorkerFactory(
        backups = { data.backups.asDailyBackup() },
        refresher = { AutoDownloadAfterRefresh(refresher, { log.e(TAG, "Auto-download failed", it) }) { autoDownloader.run() } },
        downloads = { runner },
        notifications = { notifications },
        housekeeping = {
            Housekeeping {
                cleanUp.run()
                // After the downloads' clean-up, which may have freed a kept-aside show's last download.
                removeUnusedKeptAside()
                autoDownloader.run()
            }
        },
    )

    /**
     * Safe on every start: existing schedules are kept. Call off the main thread. The refresh schedule follows its
     * setting for the life of the process.
     */
    fun schedule() {
        BackupWorker.schedule(workManager)
        CleanUpWorker.schedulePeriodic(workManager)
        scope.launch { data.settings.refreshSchedule.collect { RefreshWorker.schedulePeriodic(workManager, it) } }
    }

    /**
     * For the life of the process: clean-up runs soon after an episode is marked played (SPEC §9), and with "keep
     * starred episodes downloaded" a newly starred episode is queued at once. Call once, from the app's start.
     */
    fun watch() {
        scope.launch {
            val repos = data.awaitRepositories() ?: return@launch
            launch { repos.downloadRules.playedCount().drop(1).collect { CleanUpWorker.runSoon(workManager) } }
            launch {
                repos.downloadRules.starredCount().drop(1).collect {
                    if (data.settings.cleanUp.first().keepStarredDownloaded) runAutoDownload()
                }
            }
        }
    }

    private suspend fun runAutoDownload() {
        try {
            autoDownloader.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Auto-download failed", e)
        }
    }

    /** Settings › Downloads: stores the Wi-Fi-only choice and moves downloads still waiting to it (SPEC §9). */
    suspend fun setDownloadsWifiOnly(wifiOnly: Boolean) {
        data.settings.setDownloadsWifiOnly(wifiOnly)
        downloads.applyNetworkSetting()
    }

    /** Settings › Downloads: auto-downloads' own network rule; waiting auto-downloads move to it (SPEC §9). */
    suspend fun setAutoDownloadWifiOnly(wifiOnly: Boolean) {
        data.settings.setAutoDownloadWifiOnly(wifiOnly)
        downloads.applyNetworkSetting()
    }

    /** Settings › Downloads: clean-up numbers and "keep starred episodes downloaded", which takes effect at once. */
    suspend fun setCleanUp(settings: CleanUpSettings) {
        data.settings.setCleanUp(settings)
        if (settings.keepStarredDownloaded) runAutoDownload()
        CleanUpWorker.runSoon(workManager)
    }

    /** The owner asked: refresh [feedId], or every feed when null, as soon as there is a network. */
    fun refreshNow(feedId: Long?) = RefreshWorker.refreshNow(workManager, feedId)

    /** When the next background refresh is due, or null when none is scheduled. */
    fun nextRefreshAt(): Flow<Long?> = RefreshWorker.nextScheduledRun(workManager)

    /** Whether the refresh [refreshNow] asked for ([feedId], or all for null) is running now. */
    fun refreshRunning(feedId: Long?): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(RefreshWorker.nowName(feedId)).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    private companion object {
        const val TAG = "Jobs"
    }
}
