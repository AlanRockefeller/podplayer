/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.jobs.download.AudioProbe
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
            runner = runner,
            paths = data.paths,
            log = log,
        )
    }

    private val notifications by lazy { DownloadNotifications(appContext) }

    val workerFactory = PodplayerWorkerFactory(
        backups = { data.backups.asDailyBackup() },
        refresher = { refresher },
        downloads = { runner },
        notifications = { notifications },
    )

    /** Safe on every start: existing schedules are kept. Call off the main thread. */
    fun schedule() {
        BackupWorker.schedule(workManager)
        RefreshWorker.schedulePeriodic(workManager)
    }

    /** The owner asked: refresh [feedId], or every feed when null, as soon as there is a network. */
    fun refreshNow(feedId: Long?) = RefreshWorker.refreshNow(workManager, feedId)
}
