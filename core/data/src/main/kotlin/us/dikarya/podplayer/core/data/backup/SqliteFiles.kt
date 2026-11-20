/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.EpisodeActionKind

/** Plain SQLite connections outside Room, for checks and copies. They never delete a damaged file. */
internal object RawDatabase {
    private val keepFile = DatabaseErrorHandler { /* The framework default deletes the file. Keep it. */ }

    /**
     * Opens [file] for [block]. [wal] matches Room's journal mode, for the live database; staged copies use
     * the rollback journal so nothing is left beside them.
     */
    fun <T> use(file: File, wal: Boolean, block: (SQLiteDatabase) -> T): T {
        var flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        if (wal) flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        val db = SQLiteDatabase.openDatabase(file.path, null, flags, keepFile)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    fun deleteWithSidecars(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
    }
}

sealed interface Health {
    data object Missing : Health

    data class Healthy(val userVersion: Int) : Health

    data class Damaged(val reason: String) : Health
}

/** SPEC §7: integrity check on start (and on every backup before it is restored). */
internal object IntegrityCheck {
    fun check(file: File, wal: Boolean = true): Health {
        if (!file.exists()) return Health.Missing
        return try {
            RawDatabase.use(file, wal) { db ->
                val result = db.rawQuery("PRAGMA integrity_check(20)", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) ?: "") }
                }
                if (result == listOf("ok")) Health.Healthy(db.version) else Health.Damaged(result.joinToString("; ").take(500))
            }
        } catch (e: RuntimeException) {
            // SQLiteException for "not a database", "malformed" and so on. The message names no URLs.
            Health.Damaged("${e.javaClass.simpleName}: ${e.message?.take(200)}")
        }
    }
}

/**
 * Describes a database's structure the way Room's own validation sees it (tables, columns with type, not-null
 * and primary key, foreign keys, indexes) plus every view and trigger and Room's identity hash, so a restored
 * file can be compared with a fresh database made by this version of the app. Table names come from the file,
 * so they are only ever passed as bound arguments.
 */
internal object SchemaCheck {
    fun describe(db: SQLiteDatabase): Set<String> {
        val out = sortedSetOf<String>()
        val objects = db.rawQuery(
            "SELECT type, name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND name <> 'android_metadata'",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }
        for ((type, name, table) in objects) {
            out += "$type $name on $table"
            if (type != "table") continue
            db.rawQuery("SELECT name, upper(type), \"notnull\", pk FROM pragma_table_info(?)", arrayOf(name)).use { c ->
                while (c.moveToNext()) out += "column $name.${c.getString(0)} ${c.getString(1)} notnull=${c.getInt(2)} pk=${c.getInt(3)}"
            }
            db.rawQuery("SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list(?)", arrayOf(name)).use { c ->
                while (c.moveToNext()) out += "fk $name.${c.getString(1)} -> ${c.getString(0)}.${c.getString(2)} ${c.getString(3)}"
            }
            val indexes = db.rawQuery("SELECT name, \"unique\" FROM pragma_index_list(?)", arrayOf(name)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
            }
            for ((index, unique) in indexes) {
                val columns = db.rawQuery("SELECT name FROM pragma_index_info(?) ORDER BY seqno", arrayOf(index)).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                out += "index $name.$index unique=$unique (${columns.joinToString()})"
            }
        }
        if (objects.any { it.second == "room_master_table" }) {
            db.rawQuery("SELECT id, identity_hash FROM room_master_table", null).use { c ->
                while (c.moveToNext()) out += "identity ${c.getLong(0)} ${c.getString(1)}"
            }
        }
        return out
    }

    private val enumColumns = listOf(
        Triple("media_files", "state", DownloadState.entries.map { it.name }),
        Triple("episode_actions", "kind", EpisodeActionKind.entries.map { it.name }),
        Triple("download_log", "event", DownloadEvent.entries.map { it.name }),
        Triple("refresh_log", "outcome", RefreshOutcome.entries.map { it.name }),
    )

    /**
     * Values that pass SQLite's own checks but would crash the app when read: enum names this version doesn't
     * know, and rows pointing at rows that don't exist. Run only after [describe] matched, so the tables and
     * columns here exist.
     */
    fun valuesAreUsable(db: SQLiteDatabase): Boolean {
        for ((table, column, names) in enumColumns) {
            val placeholders = names.joinToString(",") { "?" }
            val bad = db.rawQuery("SELECT COUNT(*) FROM $table WHERE $column NOT IN ($placeholders)", names.toTypedArray()).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            if (bad > 0) return false
        }
        return db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() }
    }
}

/**
 * A consistent copy of the open database (SPEC §7: `VACUUM INTO` or equivalent). `VACUUM INTO` needs SQLite
 * 3.27, which Android has from version 11; Android 10 ships 3.22, so there the copy is made under Room's write
 * lock after a full checkpoint has emptied the write-ahead log.
 */
internal object DatabaseSnapshot {
    enum class Method { VacuumInto, CheckpointAndCopy }

    fun methodFor(sqliteVersion: String): Method {
        val parts = sqliteVersion.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return Method.CheckpointAndCopy
        val (major, minor) = parts
        return if (major > 3 || major == 3 && minor >= 27) Method.VacuumInto else Method.CheckpointAndCopy
    }

    fun methodFor(db: SupportSQLiteDatabase): Method =
        methodFor(db.query("SELECT sqlite_version()").use { if (it.moveToFirst()) it.getString(0) else "" })

    fun take(db: SupportSQLiteDatabase, target: File, method: Method = methodFor(db)) {
        target.parentFile?.mkdirs()
        RawDatabase.deleteWithSidecars(target)
        when (method) {
            Method.VacuumInto -> db.execSQL("VACUUM INTO ?", arrayOf(target.path))
            Method.CheckpointAndCopy -> checkpointAndCopy(db, target)
        }
    }

    private fun checkpointAndCopy(db: SupportSQLiteDatabase, target: File) {
        val path = db.path ?: throw IOException("not a file database")
        val wal = File("$path-wal")
        repeat(5) {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            // An exclusive transaction holds the write lock: nobody can add to the log while the file is copied.
            db.beginTransaction()
            try {
                if (!wal.exists() || wal.length() == 0L) {
                    File(path).copyTo(target, overwrite = true)
                    return
                }
            } finally {
                db.endTransaction()
            }
        }
        throw IOException("the database kept changing during the backup")
    }
}
