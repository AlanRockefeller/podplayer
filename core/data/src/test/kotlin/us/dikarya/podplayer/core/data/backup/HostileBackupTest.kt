/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import us.dikarya.podplayer.core.data.db.PodDatabase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.TestLayers
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.data.repo.SubscribeResult

/**
 * A backup is whatever file the owner picks, so a restore treats it as hostile input (CLAUDE.md): every
 * malformed, oversized or tampered archive is refused with a typed reason and the library stays as it was.
 */
@RunWith(AndroidJUnit4::class)
class HostileBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private val layers by lazy { TestLayers(tmp.root, clock) }
    private lateinit var layer: DataLayer
    private lateinit var good: Map<String, ByteArray>

    @Before
    fun setUp() = runBlocking {
        layer = layers.open()
        val repos = layer.awaitRepositories()!!
        val id = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) }))
        val out = ByteArrayOutputStream()
        assertEquals(null, layer.backups.exportTo(out))
        good = unzip(out.toByteArray())
        assertEquals(setOf(BackupArchive.MANIFEST, BackupArchive.DATABASE, BackupArchive.SETTINGS), good.keys)
    }

    @After
    fun tearDown() = runBlocking { layer.close() }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                map[entry.name] = zip.readBytes()
            }
        }
        return map
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun with(name: String, data: ByteArray) = zip(good.toMutableMap().apply { put(name, data) }.toList())

    private fun without(name: String) = zip(good.filterKeys { it != name }.toList())

    /** The good database with [sql] run on it. */
    private fun tamperedDatabase(vararg sql: String): ByteArray {
        val file = File(tmp.root, "tamper-${Random.nextInt()}.db").apply { writeBytes(good.getValue(BackupArchive.DATABASE)) }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        sql.forEach { db.execSQL(it) }
        db.close()
        return file.readBytes()
    }

    private fun manifest(edit: (String) -> String) = edit(good.getValue(BackupArchive.MANIFEST).decodeToString()).toByteArray()

    private fun stage(bytes: ByteArray): StageResult = runBlocking { layer.restore { bytes.inputStream() } }

    private fun refused(problem: RestoreProblem, bytes: ByteArray) {
        assertEquals(StageResult.Refused(problem), stage(bytes))
        assertFalse("nothing may be staged", layers.files.pendingRestoreMarker.exists())
    }

    @Test
    fun theGoodArchiveIsAccepted() {
        assertTrue(stage(zip(good.toList())) is StageResult.Staged)
    }

    @Test
    fun aProviderThatThrowsSomethingOtherThanIOExceptionIsARefusalNotACrash() {
        // Found while fixing the CodeRabbit review (PR 1): only IOException was caught, so anything else escaped
        // into the restore dialog's coroutine on the app scope, which has no handler.
        val result = runBlocking { layer.restore { throw IllegalStateException("provider went away") } }
        assertEquals(StageResult.Refused(RestoreProblem.Unreadable), result)
        assertFalse("nothing may be staged", layers.files.pendingRestoreMarker.exists())
    }

    @Test
    fun notAZipAtAll() {
        refused(RestoreProblem.NotABackup, Random(1).nextBytes(10_000))
        refused(RestoreProblem.NotABackup, ByteArray(0))
        refused(RestoreProblem.NotABackup, "PK".toByteArray() + Random(2).nextBytes(500))
    }

    @Test
    fun missingParts() {
        refused(RestoreProblem.NotABackup, without(BackupArchive.MANIFEST))
        refused(RestoreProblem.NotABackup, without(BackupArchive.DATABASE))
        refused(RestoreProblem.NotABackup, without(BackupArchive.SETTINGS))
    }

    @Test
    fun brokenManifestOrSettings() {
        refused(RestoreProblem.NotABackup, with(BackupArchive.MANIFEST, "{oops".toByteArray()))
        refused(RestoreProblem.NotABackup, with(BackupArchive.MANIFEST, "[]".toByteArray()))
        refused(RestoreProblem.NotABackup, with(BackupArchive.SETTINGS, "not settings".toByteArray()))
    }

    @Test
    fun newerThanThisApp() {
        refused(RestoreProblem.NewerThanApp, with(BackupArchive.MANIFEST, manifest { it.replace("\"format\":1", "\"format\":2") }))
        refused(RestoreProblem.NewerThanApp, with(BackupArchive.MANIFEST, manifest {
            val current = "\"schemaVersion\":${PodDatabase.VERSION}"
            assertTrue("the manifest names the current schema", current in it)
            it.replace(current, "\"schemaVersion\":99")
        }))
        refused(RestoreProblem.NewerThanApp, with(BackupArchive.DATABASE, tamperedDatabase("PRAGMA user_version = 99")))
    }

    @Test
    fun manifestAndDatabaseDisagree() {
        refused(RestoreProblem.UnexpectedContent, with(BackupArchive.DATABASE, tamperedDatabase("PRAGMA user_version = 0")))
    }

    @Test
    fun duplicateAndExcessEntries() {
        // ZipOutputStream refuses duplicate names, so write a look-alike and patch the name in place.
        val decoy = zip(good.toList() + ("manifesX.json" to good.getValue(BackupArchive.MANIFEST)))
        val patched = String(decoy, Charsets.ISO_8859_1).replace("manifesX.json", "manifest.json").toByteArray(Charsets.ISO_8859_1)
        refused(RestoreProblem.NotABackup, patched)
        refused(RestoreProblem.NotABackup, zip(good.toList() + (1..40).map { "extra-$it.txt" to ByteArray(1) }))
    }

    @Test
    fun pathTraversalNamesAreNeverWrittenAnywhere() {
        val evil = zip(good.toList() + ("../../../../evil.txt" to "pwned".toByteArray()) + ("/tmp/evil2.txt" to "x".toByteArray()))
        assertTrue(stage(evil) is StageResult.Staged)
        assertFalse(tmp.root.walkTopDown().any { it.name.startsWith("evil") })
    }

    @Test
    fun decompressionBombIsStoppedAtTheLimit() = runBlocking {
        layer.close()
        val small = layers.open(BackupLimits(maxDatabaseBytes = 1L shl 20, maxOtherBytes = 64 * 1024))
        layer = small
        small.awaitRepositories()
        // 64 MiB of zeros compresses to about 64 KiB.
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE))
            val zeros = ByteArray(1 shl 20)
            repeat(64) { zip.write(zeros) }
            zip.closeEntry()
        }
        refused(RestoreProblem.TooLarge, bomb.toByteArray())
        refused(RestoreProblem.TooLarge, with(BackupArchive.SETTINGS, ByteArray(200 * 1024) { ' '.code.toByte() }))
    }

    @Test
    fun damagedDatabase() {
        refused(RestoreProblem.Damaged, with(BackupArchive.DATABASE, Random(3).nextBytes(64 * 1024)))
        val truncated = good.getValue(BackupArchive.DATABASE).let { it.copyOf(it.size / 2) }
        refused(RestoreProblem.Damaged, with(BackupArchive.DATABASE, truncated))
    }

    @Test
    fun tamperedSchemaIsRefused() {
        refused(
            RestoreProblem.UnexpectedContent,
            with(BackupArchive.DATABASE, tamperedDatabase("CREATE TRIGGER t AFTER INSERT ON episodes BEGIN DELETE FROM feeds; END")),
        )
        refused(RestoreProblem.UnexpectedContent, with(BackupArchive.DATABASE, tamperedDatabase("CREATE VIEW v AS SELECT * FROM feeds")))
        refused(RestoreProblem.UnexpectedContent, with(BackupArchive.DATABASE, tamperedDatabase("CREATE TABLE extra (x)")))
        refused(RestoreProblem.UnexpectedContent, with(BackupArchive.DATABASE, tamperedDatabase("ALTER TABLE feeds DROP COLUMN author")))
        refused(RestoreProblem.UnexpectedContent, with(BackupArchive.DATABASE, tamperedDatabase("DROP INDEX index_episodes_feedId_guid")))
    }

    @Test
    fun valuesThatWouldCrashTheAppAreRefused() {
        refused(
            RestoreProblem.UnexpectedContent,
            with(
                BackupArchive.DATABASE,
                tamperedDatabase("INSERT INTO media_files (episodeId, state, bytesDownloaded, updatedAt) VALUES (1, 'Exploded', 0, 0)"),
            ),
        )
        refused(
            RestoreProblem.UnexpectedContent,
            with(BackupArchive.DATABASE, tamperedDatabase("INSERT INTO episode_actions (kind, at, deviceId, synced) VALUES ('Teleport', 0, 'x', 0)")),
        )
    }

    @Test
    fun aRefusedRestoreLeavesTheLibraryAlone() = runBlocking {
        refused(RestoreProblem.Damaged, with(BackupArchive.DATABASE, Random(4).nextBytes(10_000)))
        layer.close()
        layer = layers.open()
        assertEquals(1, layer.awaitRepositories()!!.feeds.feeds().first().size)
    }
}
