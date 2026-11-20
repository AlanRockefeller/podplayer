/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data

import android.content.Context
import androidx.core.net.toUri
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.backup.BackupCandidate
import us.dikarya.podplayer.core.data.backup.BackupFolder
import us.dikarya.podplayer.core.data.backup.BackupLimits
import us.dikarya.podplayer.core.data.backup.BackupManager
import us.dikarya.podplayer.core.data.backup.SafBackupFolder
import us.dikarya.podplayer.core.data.backup.StageResult
import us.dikarya.podplayer.core.data.db.DatabaseOpener
import us.dikarya.podplayer.core.data.db.PendingRestore
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.db.PodDatabases
import us.dikarya.podplayer.core.data.db.Prepared
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.media.MediaPaths
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.DeviceSettings

enum class OpenProblem {
    /** The database was written by a newer version of the app. It is left untouched. */
    NewerThanApp,

    /** A migration was needed and the backup before it failed, so the database was not migrated. */
    BackupBeforeMigrationFailed,

    CouldNotOpen,
}

sealed interface DataState {
    data object Opening : DataState

    class Ready internal constructor(val repositories: Repositories, internal val database: PodDatabase) : DataState

    /** The start-up integrity check failed. The file is kept; offer [backups] (newest first) or a fresh start. */
    data class Damaged(val reason: String, val backups: List<BackupCandidate>) : DataState

    data class Failed(val problem: OpenProblem) : DataState
}

/**
 * The data layer as the app sees it: settings, the database (opened off the main thread after the start-up
 * checks), repositories, backups, media paths. Built by `AppGraph`; [start] once per process.
 */
class DataLayer(
    context: Context,
    scope: CoroutineScope,
    private val appVersion: String,
    private val log: AppLog,
    private val clock: () -> Long = System::currentTimeMillis,
    filesProvider: () -> DataFiles = { DataFiles.from(context) },
    ownerFolder: ((String) -> BackupFolder)? = null,
    backupLimits: BackupLimits = BackupLimits(),
    /** Tests only: pretend the app expects a newer schema, to exercise the backup before migration. */
    internal val targetVersion: Int = PodDatabase.VERSION,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job + io)
    private val openLock = Mutex()

    /** Touches the disk on first use; only ever read off the main thread. */
    val files: DataFiles by lazy(filesProvider)

    val settings: AppSettings = AppSettings.create(this.scope) { files.settingsFile }
    val device: DeviceSettings = DeviceSettings.create(this.scope) { files.deviceSettingsFile }
    val paths = MediaPaths { files.filesDir }

    private val _state = MutableStateFlow<DataState>(DataState.Opening)
    val state: StateFlow<DataState> = _state.asStateFlow()

    val backups = BackupManager(
        context = appContext,
        files = { files },
        database = { awaitDatabase() },
        settings = settings,
        device = device,
        ownerFolder = ownerFolder ?: { uri -> SafBackupFolder(appContext.contentResolver, uri.toUri()) },
        clock = clock,
        appVersion = appVersion,
        log = log,
        limits = backupLimits,
        io = io,
    )

    fun start(): Job = scope.launch {
        openLock.withLock {
            try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave the state at Opening: everything waiting for the database would wait forever.
                log.e(TAG, "Opening the library failed unexpectedly", e)
                _state.value = DataState.Failed(OpenProblem.CouldNotOpen)
            }
        }
    }

    /** The repositories once the database is open, or null if it couldn't be opened. */
    suspend fun awaitRepositories(): Repositories? = (state.first { it !is DataState.Opening } as? DataState.Ready)?.repositories

    /**
     * The library as it changes: null while it is damaged or couldn't be opened, then the repositories once
     * [startFresh] opens an empty one. Screens observe this rather than [awaitRepositories] once, so they don't
     * stay on "library closed" after recovery.
     */
    val repositories: Flow<Repositories?> =
        state.filter { it !is DataState.Opening }.map { (it as? DataState.Ready)?.repositories }.distinctUntilChanged()

    private suspend fun awaitDatabase(): PodDatabase? = (state.first { it !is DataState.Opening } as? DataState.Ready)?.database

    /** Stages [candidate] for the next start (the caller restarts the app on [StageResult.Staged]). */
    suspend fun restore(candidate: BackupCandidate): StageResult = backups.stage(candidate)

    /** Stages a backup file the owner picked. */
    suspend fun restore(open: () -> InputStream): StageResult = backups.stage(open)

    /**
     * After [DataState.Damaged]: keeps the damaged file aside and starts with an empty library. False when the
     * damaged file couldn't be kept aside: then nothing else happens and the state stays [DataState.Damaged], so
     * the library is never emptied without its old file being kept. True says only that the file was kept: whether
     * the empty library then opened is in [state] (Ready, or Failed, which the shell shows as its own dialog).
     */
    suspend fun startFresh(): Boolean = withContext(io) {
        openLock.withLock {
            if (state.value !is DataState.Damaged) return@withLock true
            try {
                DatabaseOpener.keepAside(files.databaseFile, files.damagedDir, "damaged-${clock()}.db")
            } catch (e: IOException) {
                log.e(TAG, "Couldn't keep the damaged database aside; the library was not emptied", e)
                return@withLock false
            }
            log.w(TAG, "Started with an empty library; the damaged database was kept")
            openRoom(null)
            true
        }
    }

    /** Closes the database and stops the settings stores, as process death would. For tests. */
    suspend fun close() {
        (state.value as? DataState.Ready)?.database?.close()
        log.flush()
        job.cancelAndJoin()
    }

    private suspend fun open() {
        val f = files
        val applied = try {
            PendingRestore.apply(f, clock())
        } catch (e: Exception) {
            log.e(TAG, "Applying the staged restore failed", e)
            null
        }
        if (applied != null) log.i(TAG, "Applying a restored backup")
        val prepared = DatabaseOpener.prepare(f.databaseFile, targetVersion) { from ->
            log.i(TAG, "Database version $from needs migrating to $targetVersion; backing up first")
            backups.backupClosedDatabase(f.databaseFile, from, "before-v$targetVersion")
        }
        when (prepared) {
            is Prepared.Damaged -> {
                log.e(TAG, "The database failed its integrity check: ${prepared.reason}")
                _state.value = DataState.Damaged(prepared.reason, backups.candidates())
            }
            is Prepared.NewerThanApp -> {
                log.e(TAG, "The database is version ${prepared.version}, newer than this app")
                _state.value = DataState.Failed(OpenProblem.NewerThanApp)
            }
            Prepared.BackupFailed -> _state.value = DataState.Failed(OpenProblem.BackupBeforeMigrationFailed)
            Prepared.Fresh, Prepared.Current, is Prepared.Migrate -> openRoom(applied)
        }
    }

    private suspend fun openRoom(applied: PendingRestore.Applied?) {
        val db = PodDatabases.builder(appContext, files.databaseFile) {
            log.e(TAG, "SQLite reported a damaged database; the file is kept for the next start's check")
        }.build()
        try {
            // Opening runs any migrations now, not on the first query from some screen.
            db.openHelper.writableDatabase
        } catch (e: Exception) {
            db.close()
            log.e(TAG, "Couldn't open the database", e)
            _state.value = DataState.Failed(OpenProblem.CouldNotOpen)
            return
        }
        val repositories = Repositories(db, clock) { device.deviceId() }
        if (applied != null) {
            applied.settingsJson?.let { if (!settings.restore(it)) log.w(TAG, "The restored settings couldn't be read") }
            val reconciled = repositories.media.reconcileWithDisk(paths)
            PendingRestore.finish(files)
            log.i(
                TAG,
                "Restore finished; ${reconciled.droppedRecords} download records without their files were dropped " +
                    "and ${reconciled.deletedFiles} media files no record owned were deleted",
            )
        }
        _state.value = DataState.Ready(repositories, db)
    }

    private companion object {
        const val TAG = "Data"
    }
}
