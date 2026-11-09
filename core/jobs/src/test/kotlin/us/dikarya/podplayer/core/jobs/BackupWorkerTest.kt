/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeBackups(private val outcome: DailyBackupOutcome) : DailyBackup {
        var runs = 0

        override suspend fun runIfDue(): DailyBackupOutcome {
            runs++
            return outcome
        }
    }

    private fun run(backups: DailyBackup): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(PodplayerWorkerFactory { backups })
            .build()
            .doWork()
    }

    @Test
    fun successAndNothingDueBothSucceed() {
        assertEquals(ListenableWorker.Result.success(), run(FakeBackups(DailyBackupOutcome.Done)))
        assertEquals(ListenableWorker.Result.success(), run(FakeBackups(DailyBackupOutcome.NotDue)))
    }

    @Test
    fun aFailedBackupIsRetriedLater() {
        assertEquals(ListenableWorker.Result.retry(), run(FakeBackups(DailyBackupOutcome.Failed)))
    }

    @Test
    fun anExceptionNeverCrashesTheWorker() {
        val throwing = object : DailyBackup {
            override suspend fun runIfDue(): DailyBackupOutcome = throw IllegalStateException("boom")
        }
        assertEquals(ListenableWorker.Result.retry(), run(throwing))
    }

    class OtherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    @Test
    fun theFactoryLeavesOtherWorkersToTheDefault() {
        val worker = TestListenableWorkerBuilder<OtherWorker>(context)
            .setWorkerFactory(PodplayerWorkerFactory { FakeBackups(DailyBackupOutcome.Done) })
            .build()
        assertTrue(worker is OtherWorker)
    }

    @Test
    fun schedulingIsUniqueAndKeepsTheExistingSchedule() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)
        BackupWorker.schedule(workManager)
        val first = workManager.getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME).get().single()
        BackupWorker.schedule(workManager)
        val again = workManager.getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME).get().single()
        assertEquals(first.id, again.id)
        assertEquals(WorkInfo.State.ENQUEUED, again.state)
        assertTrue(again.periodicityInfo!!.repeatIntervalMillis >= 24L * 3600 * 1000)
        assertTrue(again.constraints.requiresBatteryNotLow())
        assertTrue(again.constraints.requiresStorageNotLow())
    }
}
