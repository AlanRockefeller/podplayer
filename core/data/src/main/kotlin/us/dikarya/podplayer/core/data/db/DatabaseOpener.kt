/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.backup.Health
import us.dikarya.podplayer.core.data.backup.IntegrityCheck

/** What the start-up check decided about the database file. */
sealed interface Prepared {
    /** No database yet (or an empty file): Room creates one. */
    data object Fresh : Prepared

    data object Current : Prepared

    /** Older than this app; backed up, Room migrates it next. */
    data class Migrate(val from: Int) : Prepared

    data class Damaged(val reason: String) : Prepared

    data class NewerThanApp(val version: Int) : Prepared

    /** Older than this app, and the backup before migrating failed, so it is not migrated. */
    data object BackupFailed : Prepared
}

/** The start-up sequence before Room opens the database (SPEC §7). */
internal object DatabaseOpener {
    /**
     * Checks [file] with a separate connection that never deletes it: integrity, then version. A database older
     * than [targetVersion] is only let through after [backupBeforeMigration] succeeded.
     */
    suspend fun prepare(file: File, targetVersion: Int, backupBeforeMigration: suspend (fromVersion: Int) -> Boolean): Prepared =
        when (val health = IntegrityCheck.check(file)) {
            Health.Missing -> Prepared.Fresh
            is Health.Damaged -> Prepared.Damaged(health.reason)
            is Health.Healthy -> when {
                health.userVersion == 0 -> Prepared.Fresh
                health.userVersion > targetVersion -> Prepared.NewerThanApp(health.userVersion)
                health.userVersion < targetVersion ->
                    if (backupBeforeMigration(health.userVersion)) Prepared.Migrate(health.userVersion) else Prepared.BackupFailed
                else -> Prepared.Current
            }
        }

    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    /**
     * Moves a database and its side files into [dir] under [name], keeping the newest [keep] such copies.
     *
     * The side files move first and the database last, so an interruption (process death) always leaves the
     * database where it was: the next call finishes the move, adopting side files an earlier call already
     * moved, so they stay with their database. Side files whose database is gone are moved out of the way too
     * (under a name that is never adopted), so a new database is never put next to another one's write-ahead log.
     */
    fun keepAside(database: File, dir: File, name: String, keep: Int = 3) {
        val strays = SIDECARS.map { File(database.path + it) }.filter { it.exists() }
        if (!database.exists() && strays.isEmpty()) return
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create ${dir.name}")
        if (!database.exists()) {
            for (stray in strays) move(stray, File(dir, "$name.orphan${stray.name.removePrefix(database.name)}"))
            return
        }
        // Side files of an interrupted earlier move: their database is still here, at [database].
        for (suffix in SIDECARS) {
            dir.listFiles { f -> f.name.endsWith(".db$suffix") && !File(dir, f.name.removeSuffix(suffix)).exists() }
                ?.forEach { move(it, File(dir, name + suffix)) }
        }
        for (suffix in SIDECARS) {
            val from = File(database.path + suffix)
            if (from.exists()) move(from, File(dir, name + suffix))
        }
        move(database, File(dir, name))
        dir.listFiles { f -> f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { old -> (listOf("") + SIDECARS).forEach { File(old.path + it).delete() } }
    }

    /** True when a side file (write-ahead log, shared memory, journal) sits next to where [database] would be. */
    fun hasSidecars(database: File): Boolean = SIDECARS.any { File(database.path + it).exists() }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * A restore staged by BackupManager, applied at the very start of the next launch, before anything opens the
 * database. The marker file is written last when staging and removed last here, so an interruption at any
 * point either leaves the old library or finishes the restore on the following start.
 */
internal object PendingRestore {
    class Applied(val settingsJson: String?)

    fun apply(files: DataFiles, now: Long): Applied? {
        if (!files.pendingRestoreMarker.exists()) {
            // Leftovers of a staging that never finished.
            files.restoreDir.listFiles()?.forEach { it.delete() }
            return null
        }
        val staged = files.stagedDatabase
        if (staged.exists()) {
            val db = files.databaseFile
            db.parentFile?.mkdirs()
            // The library being replaced is kept (a safety backup was also written when staging).
            DatabaseOpener.keepAside(db, files.damagedDir, "replaced-$now.db")
            // SQLite would read another database's write-ahead log as this one's.
            if (db.exists() || DatabaseOpener.hasSidecars(db)) throw IOException("the replaced library wasn't fully moved aside")
            try {
                Files.move(staged.toPath(), db.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                staged.copyTo(db, overwrite = true)
                staged.delete()
            }
        }
        return Applied(files.stagedSettings.takeIf { it.isFile }?.readText()?.take(1024 * 1024))
    }

    fun finish(files: DataFiles) {
        files.stagedSettings.delete()
        files.pendingRestoreMarker.delete()
    }
}
