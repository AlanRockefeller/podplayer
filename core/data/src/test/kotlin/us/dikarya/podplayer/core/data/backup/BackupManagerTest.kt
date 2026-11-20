/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.appContext
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.BackupFailure
import us.dikarya.podplayer.core.data.settings.DeviceSettings
import us.dikarya.podplayer.core.data.settings.ThemeMode

/** BackupManager on its own, with settings stores the test controls. */
@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val files by lazy { DataFiles.under(File(tmp.root, "app")) }
    private val log by lazy { AppLog({ File(tmp.root, "logs") }, clock, echo = false) }
    private val device by lazy { DeviceSettings.create(scope) { files.deviceSettingsFile } }
    private lateinit var db: PodDatabase

    @After
    fun tearDown() = runBlocking {
        if (::db.isInitialized) db.close()
        job.cancelAndJoin()
        log.close()
    }

    private fun manager(settings: AppSettings): BackupManager {
        db = openDatabase(files.databaseFile.apply { parentFile!!.mkdirs() })
        db.openHelper.writableDatabase
        return BackupManager(
            context = appContext(),
            files = { files },
            database = { db },
            settings = settings,
            device = device,
            ownerFolder = { FileBackupFolder(File(it)) },
            clock = clock,
            appVersion = "test",
            log = log,
            limits = BackupLimits(),
        )
    }

    /** A settings store whose file can't be read. */
    private object UnreadableStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("the settings file can't be read") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("the settings file can't be written")
    }

    @Test
    fun unreadableSettingsFailTheBackupInsteadOfBackingUpNoSettings() = runBlocking {
        // CodeRabbit review (PR 3): a read error became an empty snapshot, so the backup "succeeded" and restoring
        // it would have cleared every setting.
        val settings = AppSettings(UnreadableStore)
        assertEquals("screens still see the defaults", ThemeMode.DEFAULT, settings.themeMode.first())
        val report = manager(settings).backupNow()
        assertEquals(BackupProblem.SnapshotFailed, report.problem)
        assertEquals(null, report.name)
        assertTrue(files.localBackupsDir.list().orEmpty().isEmpty())
        assertEquals(BackupFailure.SnapshotFailed, device.backupStatusNow().lastFailure)
    }

    @Test
    fun aLongTagStillGetsADistinctNameForASecondBackupInTheSameSecond() = runBlocking {
        // CodeRabbit review (PR 3): the tag was cut to 40 characters after "-2" was added, so a name clash with
        // a long tag repeated the same name forever while holding the backup mutex.
        val tag = "t".repeat(60)
        assertNotEquals(BackupNames.create(clock.now, tag), BackupNames.create(clock.now, tag, sequence = 2))
        assertNotNull(BackupNames.parse(BackupNames.create(clock.now, tag, sequence = 12)))
        val manager = manager(AppSettings.create(scope) { files.settingsFile })
        val first = manager.backupNow(tag)
        val second = manager.backupNow(tag)
        assertEquals(null, first.problem)
        assertEquals(null, second.problem)
        assertNotEquals(first.name, second.name)
        assertEquals(2, files.localBackupsDir.list()!!.count { BackupNames.parse(it) != null })
    }
}
