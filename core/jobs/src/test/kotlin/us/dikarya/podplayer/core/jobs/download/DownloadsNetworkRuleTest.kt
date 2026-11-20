/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.appContext
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.xmlResponse
import us.dikarya.podplayer.core.model.DownloadState

/**
 * A Wi-Fi-only change reaches every download that will still run: one waiting to retry after a dropped connection
 * (its record is Paused) and one running now (the rule applies to its next attempt).
 */
@RunWith(AndroidJUnit4::class)
class DownloadsNetworkRuleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = appContext()
    private lateinit var workManager: WorkManager
    private val executor = Executors.newSingleThreadExecutor()

    /** Every download worker started here waits for this, so its work stays RUNNING. */
    private val release = CompletableDeferred<Unit>()
    private val started = CompletableDeferred<Unit>()

    private inner class HeldWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            started.complete(Unit)
            release.await()
            return Result.success()
        }
    }

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                HeldWorker(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(executor).setWorkerFactory(factory).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        release.complete(Unit)
        WorkManagerTestInitHelper.closeWorkDatabase()
        executor.shutdownNow()
    }

    private fun info(episode: Long): WorkInfo = workManager.getWorkInfosForUniqueWork(Downloads.workName(episode)).get().single()

    private fun withEnv(block: (TestEnv, Downloads, Long) -> Unit) = TestEnv(File(tmp.root, "env")).use { env ->
        val downloads = Downloads(
            workManager = { workManager },
            repositories = { env.data.awaitRepositories() },
            wifiOnly = { env.data.settings.downloadsWifiOnly.first() },
            autoWifiOnly = { env.data.settings.autoDownloadWifiOnly.first() },
            runner = env.runner,
            paths = env.data.paths,
            log = env.log,
        )
        val feedId = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = { xmlResponse(feedXml(listOf(Item("g-1", "Episode", env.url("/ep.mp3").toString())))) }
        runBlocking { env.refresher.refresh(RefreshRequest.Feed(feedId)) }
        val episode = runBlocking { env.repos().episodes.forFeed(feedId).first().single().id }
        block(env, downloads, episode)
    }

    @Test
    fun aDownloadWaitingToRetryFollowsAWifiOnlyChange() = withEnv { env, downloads, episode ->
        runBlocking {
            env.data.settings.setDownloadsWifiOnly(false)
            downloads.enqueue(episode)
            // The connection dropped: the runner kept the bytes as Paused and the worker asked to retry.
            val record = env.repos().media.get(episode)!!
            env.repos().media.upsert(record.copy(state = DownloadState.Paused, bytesDownloaded = 1_000))
            env.data.settings.setDownloadsWifiOnly(true)
            assertEquals(1, downloads.applyNetworkSetting())
        }
        assertEquals(NetworkType.UNMETERED, info(episode).constraints.requiredNetworkType)
    }

    @Test
    fun aRunningDownloadsNextAttemptFollowsAWifiOnlyChange() = withEnv { env, downloads, episode ->
        runBlocking {
            env.data.settings.setDownloadsWifiOnly(false)
            downloads.enqueue(episode)
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info(episode).id)
            withTimeout(10_000) { started.await() }
            val record = env.repos().media.get(episode)!!
            env.repos().media.upsert(record.copy(state = DownloadState.Downloading))
            assertEquals(WorkInfo.State.RUNNING, info(episode).state)
            env.data.settings.setDownloadsWifiOnly(true)
            assertEquals(1, downloads.applyNetworkSetting())
        }
        val after = info(episode)
        assertEquals("it keeps running on the network it has", WorkInfo.State.RUNNING, after.state)
        assertEquals(NetworkType.UNMETERED, after.constraints.requiredNetworkType)
    }
}
