/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.settings.RefreshSchedule
import us.dikarya.podplayer.core.jobs.cleanup.CleanUpWorker
import us.dikarya.podplayer.core.jobs.cleanup.Housekeeping
import us.dikarya.podplayer.core.jobs.download.DownloadOutcome
import us.dikarya.podplayer.core.jobs.download.DownloadTask
import us.dikarya.podplayer.core.jobs.download.DownloadWorker
import us.dikarya.podplayer.core.jobs.download.Downloads
import us.dikarya.podplayer.core.jobs.download.StopCause
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.refresh.RefreshSummary
import us.dikarya.podplayer.core.jobs.refresh.RefreshWorker
import us.dikarya.podplayer.core.jobs.refresh.Refresher
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.model.DownloadState

/** The WorkManager side of SPEC §8 and §9: unique work, constraints, and how outcomes map to results. */
@RunWith(AndroidJUnit4::class)
class WorkersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = appContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun infos(name: String) = workManager.getWorkInfosForUniqueWork(name).get()

    private fun active(name: String) = infos(name).filter { !it.state.isFinished }

    private class FakeRefresher : Refresher {
        val requests = mutableListOf<RefreshRequest>()
        var fail = false

        override suspend fun refresh(request: RefreshRequest): RefreshSummary {
            requests += request
            if (fail) throw IllegalStateException("boom")
            return RefreshSummary(attempted = 1, updated = 1, notModified = 0, failed = 0, newEpisodes = 0)
        }
    }

    private class FakeDownload(private val outcome: DownloadOutcome) : DownloadTask {
        var ran: Long? = null

        override suspend fun run(episodeId: Long, onProgress: (Long, Long?) -> Unit, stopCause: () -> StopCause): DownloadOutcome {
            ran = episodeId
            return outcome
        }
    }

    private fun factory(refresher: Refresher = FakeRefresher(), download: DownloadTask = FakeDownload(DownloadOutcome.Completed)) =
        PodplayerWorkerFactory(
            backups = { DailyBackup { DailyBackupOutcome.NotDue } },
            refresher = { refresher },
            downloads = { download },
            notifications = { DownloadNotifications(context) },
        )

    @Test
    fun thePeriodicRefreshIsEvery15MinutesOnWifiAndHourlyOnAnyNetworkByDefault() {
        // Owner, 2026-11-18: "setting, default to 15 min on wifi and 1h on cellular" (SPEC §8 said 4 hours).
        RefreshWorker.schedulePeriodic(workManager, RefreshSchedule())
        val wifi = active(RefreshWorker.PERIODIC_WIFI_NAME).single()
        val any = active(RefreshWorker.PERIODIC_ANY_NAME).single()
        assertEquals(15L * 60 * 1000, wifi.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, wifi.constraints.requiredNetworkType)
        assertEquals(60L * 60 * 1000, any.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, any.constraints.requiredNetworkType)
        // Every start schedules again: still one of each, the same work.
        RefreshWorker.schedulePeriodic(workManager, RefreshSchedule())
        assertEquals(wifi.id, active(RefreshWorker.PERIODIC_WIFI_NAME).single().id)
        assertEquals(any.id, active(RefreshWorker.PERIODIC_ANY_NAME).single().id)
    }

    @Test
    fun theNextRefreshIsTheEarlierOfTheTwoScheduledRuns() = runBlocking {
        assertNull(RefreshWorker.nextScheduledRun(workManager).first())
        val before = System.currentTimeMillis()
        RefreshWorker.schedulePeriodic(workManager, RefreshSchedule())
        val next = RefreshWorker.nextScheduledRun(workManager).first { it != null }!!
        val wifi = active(RefreshWorker.PERIODIC_WIFI_NAME).single().nextScheduleTimeMillis
        val any = active(RefreshWorker.PERIODIC_ANY_NAME).single().nextScheduleTimeMillis
        assertEquals(minOf(wifi, any), next)
        assertTrue(next >= before)
    }

    @Test
    fun theOldFourHourRefreshGoesAndAChangedScheduleReplacesTheWork() {
        // An install from before the setting has the single 4-hour schedule.
        workManager.enqueueUniquePeriodicWork(
            RefreshWorker.OLD_PERIODIC_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<RefreshWorker>(4, java.util.concurrent.TimeUnit.HOURS).build(),
        )
        RefreshWorker.schedulePeriodic(workManager, RefreshSchedule())
        assertTrue(active(RefreshWorker.OLD_PERIODIC_NAME).isEmpty())

        // Wi-Fi every hour, never on mobile data.
        RefreshWorker.schedulePeriodic(workManager, RefreshSchedule(wifiMinutes = 60, mobileMinutes = null))
        assertEquals(60L * 60 * 1000, active(RefreshWorker.PERIODIC_WIFI_NAME).single().periodicityInfo!!.repeatIntervalMillis)
        assertTrue(active(RefreshWorker.PERIODIC_ANY_NAME).isEmpty())
    }

    @Test
    fun repeatedManualRefreshesCollapse() {
        RefreshWorker.refreshNow(workManager, feedId = null)
        RefreshWorker.refreshNow(workManager, feedId = null)
        RefreshWorker.refreshNow(workManager, feedId = 7)
        RefreshWorker.refreshNow(workManager, feedId = 7)
        assertEquals(1, infos(RefreshWorker.nowName(null)).size)
        assertEquals(1, infos(RefreshWorker.nowName(7)).size)
    }

    @Test
    fun theRefreshWorkerRunsWhatItWasAskedFor() = runBlocking {
        val refresher = FakeRefresher()
        fun worker(data: androidx.work.Data) = TestListenableWorkerBuilder<RefreshWorker>(context)
            .setWorkerFactory(factory(refresher = refresher)).setInputData(data).build()
        assertEquals(ListenableWorker.Result.success(), worker(androidx.work.Data.EMPTY).doWork())
        worker(workDataOf(RefreshWorker.KEY_ALL to true)).doWork()
        worker(workDataOf(RefreshWorker.KEY_FEED to 7L)).doWork()
        assertEquals(listOf(RefreshRequest.Due, RefreshRequest.All, RefreshRequest.Feed(7)), refresher.requests)
        refresher.fail = true
        assertEquals("an unexpected error never crashes the worker", ListenableWorker.Result.retry(), worker(androidx.work.Data.EMPTY).doWork())
    }

    private fun downloadWorker(outcome: DownloadOutcome, attempt: Int = 0, id: Long? = 5) =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setWorkerFactory(factory(download = FakeDownload(outcome)))
            .setInputData(if (id == null) androidx.work.Data.EMPTY else workDataOf(DownloadWorker.KEY_EPISODE to id))
            .setRunAttemptCount(attempt)
            .build()

    @Test
    fun downloadOutcomesMapToWorkResults() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), downloadWorker(DownloadOutcome.Completed).doWork())
        assertEquals(ListenableWorker.Result.success(), downloadWorker(DownloadOutcome.Gone).doWork())
        assertEquals(ListenableWorker.Result.failure(), downloadWorker(DownloadOutcome.Failed("no")).doWork())
        assertEquals(ListenableWorker.Result.retry(), downloadWorker(DownloadOutcome.Retry("later")).doWork())
        assertEquals(
            "transient errors give up after five attempts",
            ListenableWorker.Result.failure(),
            downloadWorker(DownloadOutcome.Retry("later"), attempt = DownloadWorker.MAX_ATTEMPTS).doWork(),
        )
        assertEquals(ListenableWorker.Result.failure(), downloadWorker(DownloadOutcome.Completed, id = null).doWork())
    }

    @Test
    fun stopReasonsAreToldApart() {
        assertEquals(StopCause.ForegroundTimeout, DownloadWorker.stopCauseFor(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
        assertEquals(StopCause.Owner, DownloadWorker.stopCauseFor(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertEquals(StopCause.System, DownloadWorker.stopCauseFor(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertEquals(StopCause.System, DownloadWorker.stopCauseFor(WorkInfo.STOP_REASON_TIMEOUT))
    }

    @Test
    fun theFactoryBuildsEveryWorker() {
        val f = factory()
        assertTrue(TestListenableWorkerBuilder<RefreshWorker>(context).setWorkerFactory(f).build() is RefreshWorker)
        assertTrue(TestListenableWorkerBuilder<DownloadWorker>(context).setWorkerFactory(f).build() is DownloadWorker)
        assertTrue(TestListenableWorkerBuilder<BackupWorker>(context).setWorkerFactory(f).build() is BackupWorker)
        assertTrue(TestListenableWorkerBuilder<CleanUpWorker>(context).setWorkerFactory(f).build() is CleanUpWorker)
    }

    @Test
    fun cleanUpRunsDailyAndSoonAfterAnEpisodeIsPlayedWithoutANetwork() = runBlocking {
        CleanUpWorker.schedulePeriodic(workManager)
        CleanUpWorker.schedulePeriodic(workManager)
        val daily = infos(CleanUpWorker.PERIODIC_NAME).single()
        assertEquals(24L * 3600 * 1000, daily.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.NOT_REQUIRED, daily.constraints.requiredNetworkType)
        CleanUpWorker.runSoon(workManager)
        CleanUpWorker.runSoon(workManager)
        assertTrue(infos(CleanUpWorker.SOON_NAME).size == 1)

        var runs = 0
        val worker = TestListenableWorkerBuilder<CleanUpWorker>(context)
            .setWorkerFactory(
                PodplayerWorkerFactory(
                    backups = { DailyBackup { DailyBackupOutcome.NotDue } },
                    refresher = { FakeRefresher() },
                    downloads = { FakeDownload(DownloadOutcome.Completed) },
                    notifications = { DownloadNotifications(context) },
                    housekeeping = { Housekeeping { runs++ } },
                ),
            ).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, runs)
    }

    private fun withEnv(block: (TestEnv, Downloads) -> Unit) {
        TestEnv(File(tmp.root, "env")).use { env ->
            val downloads = Downloads(
                workManager = { workManager },
                repositories = { env.data.awaitRepositories() },
                wifiOnly = { env.data.settings.downloadsWifiOnly.first() },
                autoWifiOnly = { env.data.settings.autoDownloadWifiOnly.first() },
                runner = env.runner,
                paths = env.data.paths,
                log = env.log,
            )
            block(env, downloads)
        }
    }

    private fun TestEnv.oneEpisode(path: String = "/feed.xml"): Pair<Long, Long> {
        val feedId = subscribe(path)
        routes[path] = { xmlResponse(feedXml(listOf(Item("g-$path", "Episode", url("/ep-$feedId.mp3").toString())))) }
        runBlocking { refresher.refresh(us.dikarya.podplayer.core.jobs.refresh.RefreshRequest.Feed(feedId)) }
        return feedId to runBlocking { repos().episodes.forFeed(feedId).first().single().id }
    }

    @Test
    fun aDownloadIsUniquePerEpisodeAndWaitsForWifiByDefault() = withEnv { env, downloads ->
        val (_, episode) = env.oneEpisode()
        runBlocking {
            assertTrue(downloads.enqueue(episode))
            downloads.enqueue(episode)
        }
        val info = infos(Downloads.workName(episode)).single()
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertEquals(DownloadState.Queued, runBlocking { env.repos().media.get(episode)!!.state })
    }

    @Test
    fun turningWifiOnlyOffAllowsAnyNetwork() = withEnv { env, downloads ->
        val (_, episode) = env.oneEpisode()
        runBlocking {
            env.data.settings.setDownloadsWifiOnly(false)
            downloads.enqueue(episode)
        }
        assertEquals(NetworkType.CONNECTED, infos(Downloads.workName(episode)).single().constraints.requiredNetworkType)
    }

    @Test
    fun changingWifiOnlyMovesWaitingDownloadsToTheNewNetworkRule() = withEnv { env, downloads ->
        val (_, episode) = env.oneEpisode()
        runBlocking { downloads.enqueue(episode) }
        val before = infos(Downloads.workName(episode)).single()
        assertEquals(NetworkType.UNMETERED, before.constraints.requiredNetworkType)

        runBlocking {
            env.data.settings.setDownloadsWifiOnly(false)
            assertEquals(1, downloads.applyNetworkSetting())
        }
        val after = infos(Downloads.workName(episode)).single()
        assertEquals("the same waiting download, not a new one", before.id, after.id)
        assertEquals(NetworkType.CONNECTED, after.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, after.state)
        assertEquals(DownloadState.Queued, runBlocking { env.repos().media.get(episode)!!.state })

        runBlocking {
            env.data.settings.setDownloadsWifiOnly(true)
            downloads.applyNetworkSetting()
        }
        assertEquals(NetworkType.UNMETERED, infos(Downloads.workName(episode)).single().constraints.requiredNetworkType)
    }

    @Test
    fun aStaleApplyCannotUndoANewerWifiOnlyChange() = TestEnv(File(tmp.root, "env")).use { env ->
        // Startup reads Wi-Fi only = off and pauses; the owner turns it on and that change is applied; then
        // startup resumes. Its stale "any network" must not win, or a waiting download runs on cellular.
        val firstRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pauseNextRead = AtomicBoolean(false)
        val downloads = Downloads(
            workManager = { workManager },
            repositories = { env.data.awaitRepositories() },
            wifiOnly = {
                val value = env.data.settings.downloadsWifiOnly.first()
                if (pauseNextRead.getAndSet(false)) {
                    firstRead.complete(Unit)
                    release.await()
                }
                value
            },
            autoWifiOnly = { env.data.settings.autoDownloadWifiOnly.first() },
            runner = env.runner,
            paths = env.data.paths,
            log = env.log,
        )
        val (_, episode) = env.oneEpisode()
        runBlocking {
            env.data.settings.setDownloadsWifiOnly(false)
            downloads.enqueue(episode)
            pauseNextRead.set(true)
            val startup = launch(Dispatchers.Default) { downloads.applyNetworkSetting() }
            firstRead.await()
            env.data.settings.setDownloadsWifiOnly(true)
            val change = launch(Dispatchers.Default) { downloads.applyNetworkSetting() }
            // Without serialising, the change finishes here, before startup resumes.
            withTimeoutOrNull(1_000) { change.join() }
            release.complete(Unit)
            startup.join()
            change.join()
        }
        assertEquals(NetworkType.UNMETERED, infos(Downloads.workName(episode)).single().constraints.requiredNetworkType)
    }

    @Test
    fun aDownloadQueuedWhileWifiOnlyIsTurnedOnFollowsTheNewSetting() = TestEnv(File(tmp.root, "env")).use { env ->
        // The owner taps Download (Wi-Fi only off is read), then turns Wi-Fi only on before the work is queued.
        // The change's apply must see that download, not run before it exists.
        val firstRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pauseNextRead = AtomicBoolean(false)
        val downloads = Downloads(
            workManager = { workManager },
            repositories = { env.data.awaitRepositories() },
            wifiOnly = {
                val value = env.data.settings.downloadsWifiOnly.first()
                if (pauseNextRead.getAndSet(false)) {
                    firstRead.complete(Unit)
                    release.await()
                }
                value
            },
            autoWifiOnly = { env.data.settings.autoDownloadWifiOnly.first() },
            runner = env.runner,
            paths = env.data.paths,
            log = env.log,
        )
        val (_, episode) = env.oneEpisode()
        runBlocking {
            env.data.settings.setDownloadsWifiOnly(false)
            pauseNextRead.set(true)
            val tap = launch(Dispatchers.Default) { downloads.enqueue(episode) }
            firstRead.await()
            env.data.settings.setDownloadsWifiOnly(true)
            val change = launch(Dispatchers.Default) { downloads.applyNetworkSetting() }
            // Without serialising, the change finds nothing waiting and finishes here.
            withTimeoutOrNull(1_000) { change.join() }
            release.complete(Unit)
            tap.join()
            change.join()
        }
        assertEquals(NetworkType.UNMETERED, infos(Downloads.workName(episode)).single().constraints.requiredNetworkType)
    }

    @Test
    fun applyingTheNetworkSettingLeavesFinishedWorkAlone() = withEnv { env, downloads ->
        val (_, episode) = env.oneEpisode()
        runBlocking {
            downloads.enqueue(episode)
            downloads.cancel(episode)
            env.data.settings.setDownloadsWifiOnly(false)
            assertEquals(0, downloads.applyNetworkSetting())
        }
        val info = infos(Downloads.workName(episode)).single()
        assertEquals(WorkInfo.State.CANCELLED, info.state)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
    }

    @Test
    fun stoppingADownloadThatHasNotStartedClearsItsRecord() = withEnv { env, downloads ->
        // On the owner's phone, "Stop download" on a download waiting for Wi-Fi cancelled the work but left the
        // record "Queued" forever, so the page kept showing "Stop download" (2026-11-12).
        val (_, episode) = env.oneEpisode()
        runBlocking {
            downloads.enqueue(episode)
            downloads.cancel(episode)
        }
        assertTrue(infos(Downloads.workName(episode)).all { it.state == WorkInfo.State.CANCELLED })
        assertNull("nothing was downloaded, so the episode is back to not downloaded", runBlocking { env.repos().media.get(episode) })
        val log = runBlocking { env.repos().logs.downloadLog(episode, 5).first() }
        assertEquals(DownloadEvent.Cancelled, log.first().event)
    }

    @Test
    fun stoppingAWaitingDownloadWithPartOfTheFileKeepsItResumable() = withEnv { env, downloads ->
        val (_, episode) = env.oneEpisode()
        runBlocking {
            downloads.enqueue(episode)
            val record = env.repos().media.get(episode)!!
            env.repos().media.upsert(record.copy(bytesDownloaded = 1_000))
            downloads.cancel(episode)
        }
        assertEquals(DownloadState.Paused, runBlocking { env.repos().media.get(episode)!!.state })
    }

    @Test
    fun anUnknownEpisodeIsNotEnqueued() = withEnv { _, downloads ->
        assertFalse(runBlocking { downloads.enqueue(12_345) })
        assertTrue(infos(Downloads.workName(12_345)).isEmpty())
    }

    @Test
    fun deleteCancelsTheWorkAndRemovesTheRecord() = withEnv { env, downloads ->
        val (_, episode) = env.oneEpisode()
        runBlocking {
            downloads.enqueue(episode)
            downloads.delete(episode)
        }
        assertTrue(infos(Downloads.workName(episode)).all { it.state == WorkInfo.State.CANCELLED })
        assertNull(runBlocking { env.repos().media.get(episode) })
    }

    @Test
    fun removingAFeedDeletesItsFilesAndLeavesOtherFeedsAlone() = withEnv { env, downloads ->
        val (feedA, episodeA) = env.oneEpisode("/a.xml")
        val (feedB, episodeB) = env.oneEpisode("/b.xml")
        env.routes.file("/ep-$feedA.mp3", TestAudio.mp3(300))
        env.routes.file("/ep-$feedB.mp3", TestAudio.mp3(300))
        runBlocking {
            for (episode in listOf(episodeA, episodeB)) {
                env.runner.queue(episode)
                env.runner.run(episode)
            }
        }
        val fileA = runBlocking { env.data.paths.resolve(env.repos().media.get(episodeA)!!.relativePath)!! }
        val fileB = runBlocking { env.data.paths.resolve(env.repos().media.get(episodeB)!!.relativePath)!! }
        assertTrue(fileA.isFile && fileB.isFile)

        runBlocking { downloads.removeFeed(feedA) }
        assertFalse(fileA.exists())
        assertFalse("the feed's folder is gone", fileA.parentFile!!.exists())
        assertTrue(fileB.isFile)
        assertNull(runBlocking { env.repos().feeds.get(feedA) })
        assertTrue(runBlocking { env.repos().feeds.get(feedB) } != null)
    }
}
