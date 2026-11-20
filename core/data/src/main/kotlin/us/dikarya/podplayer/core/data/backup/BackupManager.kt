/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.db.PodDatabases
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.BackupFailure
import us.dikarya.podplayer.core.data.settings.DeviceSettings
import us.dikarya.podplayer.core.data.settings.PreferencesSnapshot

enum class BackupLocation { AppStorage, OwnerFolder }

/** A backup that could be restored. Newest first in [BackupManager.candidates]. */
data class BackupCandidate(
    val name: String,
    /** From the file name, to the second. */
    val createdAt: Long,
    val location: BackupLocation,
    internal val entry: BackupEntry,
)

enum class FolderOutcome { Written, NotChosen, Failed }

enum class BackupProblem { DatabaseNotReady, SnapshotFailed, WriteFailed }

data class BackupReport(
    val name: String?,
    /** The app-private copy was written. */
    val appStorage: Boolean,
    val ownerFolder: FolderOutcome,
    /** Null when at least one copy was written. */
    val problem: BackupProblem?,
)

sealed interface StageResult {
    /** The restore runs at the next start; the app should restart now. [createdAt] is the backup's time. */
    data class Staged(val createdAt: Long) : StageResult

    data class Refused(val problem: RestoreProblem) : StageResult
}

/**
 * Backups (SPEC §7, LESSONS "Storage"): a snapshot of the database plus every user setting, written as one
 * zip to the app's private backup folder (newest [KEEP_LOCAL] kept) and, when the owner chose one, to their
 * Storage Access Framework folder (newest [KEEP_OWNER] kept). Rotation only ever touches files named like
 * ours. Restores are validated completely, then staged for the next start, after a safety backup.
 */
class BackupManager internal constructor(
    private val context: Context,
    private val files: () -> DataFiles,
    /** The open database, waiting while it opens; null if it couldn't be opened. */
    private val database: suspend () -> PodDatabase?,
    private val settings: AppSettings,
    private val device: DeviceSettings,
    private val ownerFolder: (String) -> BackupFolder,
    private val clock: () -> Long,
    private val appVersion: String,
    private val log: AppLog,
    private val limits: BackupLimits,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    @Volatile
    private var reference: Set<String>? = null

    // Every entry point that needs the open database waits for it *before* taking [mutex]: while an older
    // database opens, the opener needs the mutex for its pre-migration backup ([backupClosedDatabase]), so
    // waiting inside it would deadlock (Codex and CodeRabbit review, PR 3).

    suspend fun backupNow(tag: String? = null): BackupReport = withContext(io) {
        val db = database()
        mutex.withLock { backupLocked(db, tag) }
    }

    /** The daily automatic backup: runs when the last one is [DUE_AFTER_MS] old (or there is none). */
    suspend fun backupIfDue(): BackupReport? = withContext(io) {
        val db = database()
        mutex.withLock {
            val last = device.backupStatusNow().lastBackupAt
            if (last != null && clock() - last in 0 until DUE_AFTER_MS) null else backupLocked(db, null)
        }
    }

    /** Manual export to a file the owner picked. Returns null on success. The caller closes [out]. */
    suspend fun exportTo(out: OutputStream): BackupProblem? = withContext(io) {
        val db = database() ?: return@withContext BackupProblem.DatabaseNotReady
        mutex.withLock {
            val snapshot = tempFile("export")
            try {
                DatabaseSnapshot.take(db.openHelper.writableDatabase, snapshot)
                BackupArchive.write(out, manifest(PodDatabase.VERSION, clock()), snapshot, settings.snapshot())
                out.flush()
                log.i(TAG, "Exported a backup")
                null
            } catch (e: Exception) {
                log.w(TAG, "Export failed", e)
                BackupProblem.WriteFailed
            } finally {
                snapshot.delete()
            }
        }
    }

    /** Every backup in the app's folder and the owner's, newest first; the app's copy wins a name clash. */
    suspend fun candidates(): List<BackupCandidate> = withContext(io) {
        val local = listCandidates(FileBackupFolder(files().localBackupsDir), BackupLocation.AppStorage)
        val owner = device.backupStatusNow().folderUri?.let { listCandidates(ownerFolder(it), BackupLocation.OwnerFolder) }.orEmpty()
        val localNames = local.map { it.name }.toSet()
        (local + owner.filter { it.name !in localNames })
            .sortedWith(compareByDescending<BackupCandidate> { it.createdAt }.thenByDescending { it.name })
    }

    internal suspend fun stage(candidate: BackupCandidate): StageResult {
        val folder = when (candidate.location) {
            BackupLocation.AppStorage -> FileBackupFolder(files().localBackupsDir)
            BackupLocation.OwnerFolder -> device.backupStatusNow().folderUri?.let(ownerFolder)
                ?: return StageResult.Refused(RestoreProblem.Unreadable)
        }
        return stageWith { incoming -> folder.read(candidate.entry) { BackupArchive.read(it, incoming, limits) } }
    }

    /** Stages a backup file the owner picked (the caller opens it, for example through the content resolver). */
    internal suspend fun stage(open: () -> InputStream): StageResult =
        stageWith { incoming -> open().use { BackupArchive.read(it, incoming, limits) } }

    /**
     * Validates a backup completely and stages it for the next start. Nothing about the current library
     * changes unless the backup is good and a safety backup of the current library was written first.
     */
    private suspend fun stageWith(readInto: (File) -> BackupArchive.ReadResult): StageResult = withContext(io) {
        val db = database()
        mutex.withLock {
            val f = files()
            clearStaging(f)
            f.restoreDir.mkdirs()
            val incoming = File(f.restoreDir, "incoming.db")
            try {
                val read = try {
                    readInto(incoming)
                } catch (e: IOException) {
                    log.w(TAG, "Couldn't read the backup", e)
                    BackupArchive.ReadResult.Refused(RestoreProblem.Unreadable)
                } catch (e: SecurityException) {
                    log.w(TAG, "No permission to read the backup", e)
                    BackupArchive.ReadResult.Refused(RestoreProblem.Unreadable)
                }
                val ok = when (read) {
                    is BackupArchive.ReadResult.Refused -> return@withLock refuse(f, read.problem)
                    is BackupArchive.ReadResult.Ok -> read
                }
                validate(incoming, ok.manifest, ok.settingsJson)?.let { return@withLock refuse(f, it) }
                if (db != null) {
                    val safety = backupLocked(db, "before-restore")
                    if (!safety.appStorage) return@withLock refuse(f, RestoreProblem.SafetyBackupFailed)
                }
                Files.move(incoming.toPath(), f.stagedDatabase.toPath(), StandardCopyOption.REPLACE_EXISTING)
                f.stagedSettings.writeText(ok.settingsJson)
                // Written last: only a complete staging counts.
                val marker = File(f.restoreDir, "pending.tmp")
                marker.writeText(ok.manifest.createdAt.toString())
                if (!marker.renameTo(f.pendingRestoreMarker)) throw IOException("can't mark the restore")
                log.i(TAG, "Restore staged (backup from ${ok.manifest.createdAt}, schema ${ok.manifest.schemaVersion})")
                StageResult.Staged(ok.manifest.createdAt)
            } catch (e: IOException) {
                log.w(TAG, "Staging the restore failed", e)
                refuse(f, RestoreProblem.Unreadable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A hostile or odd file, or a provider that throws something other than IOException: a refusal,
                // never a crash (the restore dialog runs this on the app scope, which has no handler).
                log.w(TAG, "Staging the restore failed unexpectedly", e)
                refuse(f, RestoreProblem.Unreadable)
            } finally {
                RawDatabase.deleteWithSidecars(incoming)
            }
        }
    }

    /**
     * Backs up a database that nothing has open, before the opener lets Room migrate it (SPEC §7: always back up
     * before a migration). Returns true when at least one copy was written.
     */
    internal suspend fun backupClosedDatabase(dbFile: File, schemaVersion: Int, tag: String): Boolean = withContext(io) {
        mutex.withLock {
            val copy = tempFile("premigration")
            try {
                RawDatabase.use(dbFile, wal = true) { db -> db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
                dbFile.copyTo(copy, overwrite = true)
                val report = writeEverywhere(copy, schemaVersion, tag)
                report.problem == null
            } catch (e: Exception) {
                log.e(TAG, "Backup before migration failed", e)
                false
            } finally {
                copy.delete()
            }
        }
    }

    /** Runs under [mutex]; [db] was resolved before taking it. */
    private suspend fun backupLocked(db: PodDatabase?, tag: String?): BackupReport {
        db ?: return BackupReport(null, false, FolderOutcome.NotChosen, BackupProblem.DatabaseNotReady)
        val snapshot = tempFile("snapshot")
        try {
            try {
                DatabaseSnapshot.take(db.openHelper.writableDatabase, snapshot)
            } catch (e: Exception) {
                log.e(TAG, "Database snapshot failed", e)
                device.recordBackup(null, BackupFailure.SnapshotFailed)
                return BackupReport(null, false, FolderOutcome.NotChosen, BackupProblem.SnapshotFailed)
            }
            return writeEverywhere(snapshot, PodDatabase.VERSION, tag)
        } finally {
            RawDatabase.deleteWithSidecars(snapshot)
        }
    }

    private suspend fun writeEverywhere(dbFile: File, schemaVersion: Int, tag: String?): BackupReport {
        val now = clock()
        val manifest = manifest(schemaVersion, now)
        val settingsJson = try {
            settings.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never write a backup whose settings are a read error's empty fallback: restoring it would clear them.
            log.e(TAG, "Couldn't read the settings for the backup", e)
            device.recordBackup(null, BackupFailure.SnapshotFailed)
            return BackupReport(null, false, FolderOutcome.NotChosen, BackupProblem.SnapshotFailed)
        }
        val local = runCatching { writeTo(FileBackupFolder(files().localBackupsDir), KEEP_LOCAL, now, tag, manifest, dbFile, settingsJson) }
        local.exceptionOrNull()?.let { log.e(TAG, "Couldn't write the app's backup copy", it) }
        val folderUri = device.backupStatusNow().folderUri
        var name = local.getOrNull()
        val owner = if (folderUri == null) {
            FolderOutcome.NotChosen
        } else {
            val written = runCatching { writeTo(ownerFolder(folderUri), KEEP_OWNER, now, tag, manifest, dbFile, settingsJson) }
            written.exceptionOrNull()?.let { log.w(TAG, "Couldn't write to the backup folder", it) }
            name = written.getOrNull() ?: name
            if (written.isSuccess) FolderOutcome.Written else FolderOutcome.Failed
        }
        val anyCopy = local.isSuccess || owner == FolderOutcome.Written
        val failure = when {
            !anyCopy -> BackupFailure.WriteFailed
            owner == FolderOutcome.Failed -> BackupFailure.FolderUnreachable
            else -> null
        }
        device.recordBackup(if (anyCopy) now else null, failure)
        if (anyCopy) log.i(TAG, "Backup $name written (app storage: ${local.isSuccess}, folder: $owner)")
        return BackupReport(name, local.isSuccess, owner, if (anyCopy) null else BackupProblem.WriteFailed)
    }

    private fun writeTo(
        folder: BackupFolder,
        keep: Int,
        now: Long,
        tag: String?,
        manifest: BackupManifest,
        dbFile: File,
        settingsJson: String,
    ): String {
        val existing = folder.list()
        val taken = existing.map { it.name }.toSet()
        var name = BackupNames.create(now, tag)
        var n = 2
        while (name in taken) name = BackupNames.create(now, tag, sequence = n++)
        folder.write(name) { out -> BackupArchive.write(out, manifest, dbFile, settingsJson) }
        // Rotation: only our own names, oldest beyond [keep] deleted. Other files in the folder are never touched.
        (existing + BackupEntry(name, name))
            .mapNotNull { entry -> BackupNames.parse(entry.name)?.let { entry to it } }
            .sortedWith(compareByDescending<Pair<BackupEntry, Long>> { it.second }.thenByDescending { it.first.name })
            .drop(keep)
            .forEach { (entry, _) -> runCatching { folder.delete(entry) } }
        return name
    }

    private fun listCandidates(folder: BackupFolder, location: BackupLocation): List<BackupCandidate> =
        runCatching { folder.list() }.getOrDefault(emptyList()).mapNotNull { entry ->
            BackupNames.parse(entry.name)?.let { BackupCandidate(entry.name, it, location, entry) }
        }

    /** Null when the staged database is safe to put in place of the library. */
    private fun validate(incoming: File, manifest: BackupManifest, settingsJson: String): RestoreProblem? {
        if (PreferencesSnapshot.decode(settingsJson) == null) return RestoreProblem.NotABackup
        val version = when (val health = IntegrityCheck.check(incoming, wal = false)) {
            Health.Missing -> return RestoreProblem.NotABackup
            is Health.Damaged -> {
                log.w(TAG, "Backup database is damaged: ${health.reason}")
                return RestoreProblem.Damaged
            }
            is Health.Healthy -> health.userVersion
        }
        if (version > PodDatabase.VERSION) return RestoreProblem.NewerThanApp
        if (version != manifest.schemaVersion || version < 1) return RestoreProblem.UnexpectedContent
        if (version < PodDatabase.VERSION) {
            // An older backup: migrate the staged copy now, so a failure stops here and not at the next start.
            val migrated = runCatching {
                val db = PodDatabases.builder(context, incoming).build()
                try {
                    db.openHelper.writableDatabase
                } finally {
                    db.close()
                }
            }
            if (migrated.isFailure) {
                log.w(TAG, "Backup database didn't migrate", migrated.exceptionOrNull())
                return RestoreProblem.UnexpectedContent
            }
        }
        val usable = runCatching {
            RawDatabase.use(incoming, wal = false) { db -> SchemaCheck.describe(db) == referenceSchema() && SchemaCheck.valuesAreUsable(db) }
        }
        if (usable.getOrDefault(false) != true) {
            log.w(TAG, "Backup database doesn't match this app's schema", usable.exceptionOrNull())
            return RestoreProblem.UnexpectedContent
        }
        return null
    }

    /** What a fresh database made by this version looks like. */
    private fun referenceSchema(): Set<String> {
        reference?.let { return it }
        val file = tempFile("reference")
        try {
            val db = PodDatabases.builder(context, file).build()
            try {
                db.openHelper.writableDatabase
            } finally {
                db.close()
            }
            return RawDatabase.use(file, wal = false) { SchemaCheck.describe(it) }.also { reference = it }
        } finally {
            RawDatabase.deleteWithSidecars(file)
        }
    }

    private fun refuse(f: DataFiles, problem: RestoreProblem): StageResult {
        clearStaging(f)
        log.w(TAG, "Restore refused: $problem")
        return StageResult.Refused(problem)
    }

    private fun clearStaging(f: DataFiles) {
        if (f.pendingRestoreMarker.exists()) f.pendingRestoreMarker.delete()
        f.restoreDir.listFiles()?.forEach { it.delete() }
    }

    private fun manifest(schemaVersion: Int, at: Long) = BackupManifest(BackupArchive.FORMAT, appVersion = appVersion, schemaVersion = schemaVersion, createdAt = at)

    private fun tempFile(kind: String): File {
        val dir = files().tempDir
        dir.mkdirs()
        return File.createTempFile("$kind-", ".db", dir).also { it.delete() }
    }

    companion object {
        const val KEEP_OWNER = 7
        const val KEEP_LOCAL = 3

        /** Slightly under a day, so a worker scheduled every 24 h always finds the backup due. */
        const val DUE_AFTER_MS = 20L * 3600 * 1000
        private const val TAG = "Backup"
    }
}
