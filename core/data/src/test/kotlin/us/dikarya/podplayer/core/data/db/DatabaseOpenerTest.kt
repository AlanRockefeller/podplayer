/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.DataState
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.OpenProblem
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.TestLayers
import us.dikarya.podplayer.core.data.appContext
import us.dikarya.podplayer.core.data.backup.BackupNames
import us.dikarya.podplayer.core.data.backup.FileBackupFolder
import us.dikarya.podplayer.core.data.backup.StageResult
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.data.settings.ThemeMode

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
    fun anUnexpectedErrorWhileOpeningFailsInsteadOfWaitingForever() = runBlocking {
        // Found in M1-S5: an exception in the opening coroutine left the state at Opening, so every
        // awaitRepositories() caller (workers, screens) waited forever.
        val layer = DataLayer(
            context = appContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            appVersion = "test",
            log = layers.log,
            clock = clock,
            filesProvider = { throw IOException("storage unavailable") },
        )
        layer.start()
        withTimeout(5_000) {
            assertEquals(null, layer.awaitRepositories())
        }
        assertEquals(DataState.Failed(OpenProblem.CouldNotOpen), layer.state.value)
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
    fun theLibraryFlowFollowsStartingFresh() = runBlocking {
        // CodeRabbit review (PR 1): screens that looked up the repositories once stayed on "library closed" after
        // Start fresh opened an empty library in the same process.
        libraryWithABackup()
        damage(dbFile)
        val layer = layers.open()
        assertEquals(null, withTimeout(5_000) { layer.repositories.first() })
        val seen = async { withTimeout(5_000) { layer.repositories.first { it != null } } }
        assertTrue(layer.startFresh())
        assertTrue(seen.await()!!.feeds.feeds().first().isEmpty())
        layer.close()
    }

    @Test
    fun startingFreshFailsWithoutEmptyingTheLibraryWhenTheDamagedFileCantBeKept() = runBlocking {
        // CodeRabbit review (PR 1): an IOException from keepAside escaped startFresh and left the dialog busy.
        libraryWithABackup()
        damage(dbFile)
        val damagedBytes = dbFile.readBytes()
        val damagedDir = layers.files.damagedDir
        damagedDir.deleteRecursively()
        damagedDir.parentFile!!.mkdirs()
        damagedDir.writeText("a file where the folder should be")
        val layer = layers.open()
        assertTrue(layer.state.first { it !is DataState.Opening } is DataState.Damaged)
        assertFalse(layer.startFresh())
        assertTrue(layer.state.value is DataState.Damaged)
        assertArrayEquals(damagedBytes, dbFile.readBytes())
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
    fun aBackupStartedWhileAnOlderDatabaseOpensDoesNotDeadlockTheMigration() = runBlocking {
        // Codex and CodeRabbit review (PR 3): a backup took the backup mutex and then waited for the database,
        // while opening needed that mutex for its pre-migration backup. Neither could finish.
        libraryWithABackup()
        val gate = CountDownLatch(1)
        val layer = DataLayer(
            targetVersion = PodDatabase.VERSION + 1,
            context = appContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            appVersion = "test",
            log = layers.log,
            clock = clock,
            filesProvider = {
                gate.await()
                layers.files
            },
            ownerFolder = { FileBackupFolder(File(it)) },
        )
        layer.start()
        val backup = async(Dispatchers.IO) { layer.backups.backupNow() }
        delay(300) // The backup is now waiting for the database.
        gate.countDown()
        withTimeout(10_000) {
            assertTrue(layer.state.first { it !is DataState.Opening } is DataState.Ready)
            assertEquals(null, backup.await().problem)
        }
        assertTrue(layers.files.localBackupsDir.list()!!.any { it.contains("before-v${PodDatabase.VERSION + 1}") })
        layer.close()
    }

    @Test
    fun aRestoreThatCantBeAppliedStopsTheStartAndIsTriedAgainNextTime() = runBlocking {
        // CodeRabbit review (PR 3): a failure while applying the restore was logged and the start went on, which
        // could open an empty library that the still-pending restore would replace at the next start.
        libraryWithABackup()
        val layer = layers.open()
        layer.awaitRepositories()!!.feeds.subscribe("https://second.example.com/feed", false)
        assertTrue(layer.restore(layer.backups.candidates().first()) is StageResult.Staged)
        layer.close()
        val damagedDir = layers.files.damagedDir
        damagedDir.deleteRecursively()
        damagedDir.writeText("a file where the folder should be")

        val blocked = layers.open()
        assertEquals(DataState.Failed(OpenProblem.CouldNotOpen), blocked.state.first { it !is DataState.Opening })
        assertTrue("the restore is still pending", layers.files.pendingRestoreMarker.exists())
        blocked.close()

        damagedDir.delete()
        val next = layers.open()
        assertEquals("the restore finished", 1, next.awaitRepositories()!!.feeds.feeds().first().size)
        assertFalse(layers.files.pendingRestoreMarker.exists())
        next.close()
    }

    @Test
    fun anInterruptedMoveAsideIsFinishedWithTheSideFilesKeptTogether() {
        // CodeRabbit review (PR 3): stopping between the moves could leave a database and its write-ahead log apart.
        val dir = File(tmp.root, "aside")
        val db = File(tmp.root, "lib.db").apply { writeText("main") }
        File(db.path + "-shm").writeText("shm")
        // An earlier call marked its move, moved the log, and then the process died.
        dir.mkdirs()
        File(dir, "replaced-1.db.moving").createNewFile()
        File(dir, "replaced-1.db-wal").writeText("wal")

        DatabaseOpener.keepAside(db, dir, "replaced-2.db")
        assertFalse(db.exists())
        assertFalse(DatabaseOpener.hasSidecars(db))
        assertEquals("main", File(dir, "replaced-2.db").readText())
        assertEquals("wal", File(dir, "replaced-2.db-wal").readText())
        assertEquals("shm", File(dir, "replaced-2.db-shm").readText())
        assertFalse(File(dir, "replaced-1.db-wal").exists())
        assertTrue("no marker is left", dir.list()!!.none { it.endsWith(".moving") })
    }

    @Test
    fun sideFilesLeftByPruningAreNeverTakenForTheLibrarysOwn() {
        // CodeRabbit CLI review (PR #12): any "x.db-wal" without its "x.db" was adopted, including an old copy's log
        // left by an interrupted prune.
        val dir = File(tmp.root, "aside").apply { mkdirs() }
        File(dir, "damaged-1.db-wal").writeText("someone else's log")
        val db = File(tmp.root, "lib.db").apply { writeText("main") }
        DatabaseOpener.keepAside(db, dir, "damaged-2.db")
        assertEquals("main", File(dir, "damaged-2.db").readText())
        assertFalse(File(dir, "damaged-2.db-wal").exists())
        assertEquals("someone else's log", File(dir, "damaged-1.db-wal").readText())
    }

    @Test
    fun aDatabaseThatCantBeMovedKeepsItsSideFiles() {
        // CodeRabbit CLI review (PR #12): when the database move failed after its log had moved, Start fresh failed
        // but the next start opened the database without its log.
        val dir = File(tmp.root, "aside").apply { mkdirs() }
        File(dir, "damaged-2.db").mkdirs()
        File(dir, "damaged-2.db/blocker").writeText("a non-empty folder where the copy should go")
        val db = File(tmp.root, "lib.db").apply { writeText("main") }
        File(db.path + "-wal").writeText("wal")
        assertTrue(runCatching { DatabaseOpener.keepAside(db, dir, "damaged-2.db") }.isFailure)
        assertEquals("main", db.readText())
        assertEquals("the log was put back", "wal", File(db.path + "-wal").readText())
        assertTrue(dir.list()!!.none { it.endsWith(".moving") })
    }

    @Test
    fun theNextStartPutsBackTheLogOfAMoveThatStoppedPartWay() = runBlocking {
        libraryWithABackup()
        val dir = layers.files.damagedDir.apply { mkdirs() }
        // A Start fresh that died after moving the log (a stand-in log: SQLite ignores one with a bad header).
        File(dir, "damaged-5.db.moving").createNewFile()
        File(dir, "damaged-5.db-wal").writeBytes(ByteArray(32) { 3 })
        val layer = layers.open()
        assertEquals(1, layer.awaitRepositories()!!.feeds.feeds().first().size)
        assertFalse(File(dir, "damaged-5.db-wal").exists())
        assertTrue(dir.list()!!.none { it.endsWith(".moving") })
        layer.close()
    }

    @Test
    fun aRestoredDatabaseIsNeverPutNextToAnotherDatabasesLog() = runBlocking {
        // CodeRabbit review (PR 3): with the library already moved but its log left behind, the staged database
        // was installed beside that log, and SQLite would have read it as its own.
        libraryWithABackup()
        val layer = layers.open()
        assertTrue(layer.restore(layer.backups.candidates().first()) is StageResult.Staged)
        layer.close()
        // As an older version interrupted between moving the database and its log.
        File(dbFile.path + "-wal").writeBytes(ByteArray(64) { 7 })
        assertTrue(dbFile.renameTo(File(tmp.root, "moved-away.db")))

        val next = layers.open()
        assertEquals(1, next.awaitRepositories()!!.feeds.feeds().first().size)
        val orphan = layers.files.damagedDir.listFiles()!!.single { it.name.endsWith(".orphan-wal") }
        assertArrayEquals("the stale log was moved out of the way", ByteArray(64) { 7 }, orphan.readBytes())
        next.close()
    }

    @Test
    fun aFailureAfterTheDatabaseOpenedClosesIt() = runBlocking {
        // CodeRabbit review (PR 3): when writing the restored settings failed, the open database was never closed.
        libraryWithABackup()
        val layer = layers.open()
        assertTrue(layer.restore(layer.backups.candidates().first()) is StageResult.Staged)
        // A setting changed since the backup, so applying the backup's settings has to write.
        layer.settings.setThemeMode(ThemeMode.LIGHT)
        layer.close()
        val settingsDir = layers.files.settingsFile.parentFile!!
        assertTrue(settingsDir.setWritable(false))
        try {
            // Root ignores folder permissions; there the write can't be made to fail this way, so skip.
            assumeFalse("running with root privileges", settingsDir.canWrite())
            val next = layers.open()
            assertEquals(DataState.Failed(OpenProblem.CouldNotOpen), next.state.first { it !is DataState.Opening })
            // SQLite removes the write-ahead log when the last connection closes.
            assertFalse("the database was closed", File(dbFile.path + "-wal").exists())
            next.close()
        } finally {
            settingsDir.setWritable(true)
        }
        assertTrue("the restore is still pending", layers.files.pendingRestoreMarker.exists())
    }

    @Test
    fun aLibraryStartedFreshWhileARestoreIsReadIsBackedUpBeforeItIsReplaced() = runBlocking {
        // CodeRabbit CLI review (PR #12): the safety-backup decision used the state from before the (possibly long)
        // read, so a library opened by Start fresh meanwhile was staged over without a backup.
        libraryWithABackup()
        val backup = layers.files.localBackupsDir.listFiles()!!.single().readBytes()
        damage(dbFile)
        val layer = layers.open()
        assertTrue(layer.state.first { it !is DataState.Opening } is DataState.Damaged)
        val result = layer.restore {
            runBlocking {
                assertTrue(layer.startFresh())
                layer.awaitRepositories()!!.feeds.subscribe("https://added-meanwhile.example.com/feed", false)
            }
            backup.inputStream()
        }
        assertTrue(result is StageResult.Staged)
        assertTrue(layers.files.localBackupsDir.list()!!.any { it.contains("before-restore") })
        layer.close()
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
