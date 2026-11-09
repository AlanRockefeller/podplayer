/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.DataState
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.OpenProblem
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.TestLayers
import us.dikarya.podplayer.core.data.appContext
import us.dikarya.podplayer.core.data.backup.BackupNames
import us.dikarya.podplayer.core.data.backup.StageResult
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.data.repo.SubscribeResult

/** SPEC §7: integrity check on start with a restore offer; backup before any migration; LESSONS "Storage". */
@RunWith(AndroidJUnit4::class)
class DatabaseOpenerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private val layers by lazy { TestLayers(tmp.root, clock) }
    private val dbFile get() = layers.files.databaseFile

    private suspend fun libraryWithABackup() {
        val layer = layers.open()
        val repos = layer.awaitRepositories()!!
        val id = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) }))
        assertEquals(null, layer.backups.backupNow().problem)
        layer.close()
    }

    /** Overwrites the middle of the file, as a bad flash block would. */
    private fun damage(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(ByteArray(4096) { 0x5A })
        }
    }

    @Test
    fun aFreshInstallOpensEmpty() = runBlocking {
        val layer = layers.open()
        assertTrue(layer.awaitRepositories()!!.feeds.feeds().first().isEmpty())
        assertTrue(dbFile.exists())
        layer.close()
    }

    @Test
    fun aDamagedDatabaseIsKeptAndTheNewestBackupOffered() = runBlocking {
        libraryWithABackup()
        damage(dbFile)
        val damagedBytes = dbFile.readBytes()

        val layer = layers.open()
        assertEquals(null, layer.awaitRepositories())
        val state = layer.state.value as DataState.Damaged
        assertEquals(1, state.backups.size)
        assertArrayEquals(damagedBytes, dbFile.readBytes())

        assertTrue(layer.restore(state.backups.first()) is StageResult.Staged)
        layer.close()
        val restored = layers.open()
        assertEquals(1, restored.awaitRepositories()!!.feeds.feeds().first().size)
        restored.close()
    }

    @Test
    fun startingFreshKeepsTheDamagedFileAside() = runBlocking {
        libraryWithABackup()
        damage(dbFile)
        val damagedBytes = dbFile.readBytes()
        val layer = layers.open()
        assertTrue(layer.state.first { it !is DataState.Opening } is DataState.Damaged)
        layer.startFresh()
        assertTrue(layer.awaitRepositories()!!.feeds.feeds().first().isEmpty())
        val kept = layers.files.damagedDir.listFiles()!!.single { it.name.endsWith(".db") }
        assertArrayEquals(damagedBytes, kept.readBytes())
        layer.close()
    }

    @Test
    fun aDatabaseFromANewerAppIsRefusedAndLeftAlone() = runBlocking {
        layers.open().apply { awaitRepositories(); close() }
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        val layer = layers.open()
        assertEquals(DataState.Failed(OpenProblem.NewerThanApp), layer.state.first { it !is DataState.Opening })
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(99, it.version) }
        layer.close()
    }

    @Test
    fun anOlderDatabaseIsBackedUpBeforeMigrating() = runBlocking {
        libraryWithABackup()
        val before = dbFile.readBytes()
        val layer = layers.open()
        layer.awaitRepositories()
        layer.close()
        // Pretend the app now expects version 2: the opener must back up version 1 first.
        val backedUp = mutableListOf<Int>()
        val ready = DatabaseOpener.prepare(dbFile, targetVersion = PodDatabase.VERSION + 1) { from ->
            backedUp += from
            true
        }
        assertEquals(Prepared.Migrate(PodDatabase.VERSION), ready)
        assertEquals(listOf(PodDatabase.VERSION), backedUp)

        val refused = DatabaseOpener.prepare(dbFile, targetVersion = PodDatabase.VERSION + 1) { false }
        assertEquals(Prepared.BackupFailed, refused)
        assertTrue(before.isNotEmpty())
    }

    @Test
    fun thePreMigrationBackupIsARealRestorableBackup() = runBlocking {
        libraryWithABackup()
        // An app expecting the next version: the opener backs up the closed file before Room opens it.
        val upgraded = layers.open(targetVersion = PodDatabase.VERSION + 1)
        assertEquals(1, upgraded.awaitRepositories()!!.feeds.feeds().first().size)
        upgraded.close()
        val name = layers.files.localBackupsDir.list()!!.single { it.contains("before-v${PodDatabase.VERSION + 1}") }
        assertTrue(BackupNames.parse(name) != null)
        val check = layers.open()
        check.awaitRepositories()
        val candidate = check.backups.candidates().single { it.name == name }
        assertTrue(check.restore(candidate) is StageResult.Staged)
        check.close()
    }

    @Test
    fun roomNeverDeletesADamagedFile() {
        val file = File(tmp.root, "garbage.db").apply { writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
        val bytes = file.readBytes()
        val db = PodDatabases.builder(appContext(), file).build()
        val opened = runCatching { db.openHelper.writableDatabase }
        db.close()
        assertTrue(opened.isFailure)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aStagedRestoreIsAppliedExactlyOnce() = runBlocking {
        libraryWithABackup()
        val layer = layers.open()
        val repos = layer.awaitRepositories()!!
        repos.feeds.subscribe("https://second.example.com/feed", false)
        val candidate = layer.backups.candidates().first()
        assertTrue(layer.restore(candidate) is StageResult.Staged)
        assertTrue(layers.files.pendingRestoreMarker.exists())
        layer.close()

        val next = layers.open()
        assertEquals(1, next.awaitRepositories()!!.feeds.feeds().first().size)
        assertFalse(layers.files.pendingRestoreMarker.exists())
        next.awaitRepositories()!!.feeds.subscribe("https://third.example.com/feed", false)
        next.close()
        val again = layers.open()
        assertEquals(2, again.awaitRepositories()!!.feeds.feeds().first().size)
        again.close()
    }
}
