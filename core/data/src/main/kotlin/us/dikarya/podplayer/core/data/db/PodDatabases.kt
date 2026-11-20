/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

object PodDatabases {
    /**
     * The one way the database is opened, in the app and in tests: a file (never in-memory, so backups and the
     * integrity check see the real thing), write-ahead logging, every migration, no destructive fallback, and
     * an open helper that keeps a damaged file for the restore offer instead of deleting it.
     */
    fun builder(context: Context, file: File, onCorruption: () -> Unit = {}): RoomDatabase.Builder<PodDatabase> =
        Room.databaseBuilder(context.applicationContext, PodDatabase::class.java, file.absolutePath)
            .openHelperFactory(KeepDamagedFileFactory(onCorruption))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*Migrations.ALL.toTypedArray())
}

/**
 * The framework's default reaction to a corrupt database is to delete the file and start empty, which would
 * lose every subscription and position without a word (LESSONS "Storage"). This factory passes everything
 * through to the framework helper except that callback: the file stays, the error reaches the caller, and the
 * integrity check on the next start offers a restore.
 */
internal class KeepDamagedFileFactory(
    private val onCorruption: () -> Unit,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val keeping = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(KeepFileCallback(configuration.callback, onCorruption))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(keeping)
    }
}

internal class KeepFileCallback(
    private val inner: SupportSQLiteOpenHelper.Callback,
    private val onCorruption: () -> Unit,
) : SupportSQLiteOpenHelper.Callback(inner.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)

    override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onDowngrade(db, oldVersion, newVersion)

    override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

    /** Deliberately doesn't call super, which deletes the database files. */
    override fun onCorruption(db: SupportSQLiteDatabase) {
        onCorruption()
    }
}
