/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data

import android.content.Context
import java.io.File
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.media.MediaPaths
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.DeviceSettings

/**
 * Where everything lives. All of it is app-private internal storage (SPEC §7). Building it touches the disk
 * (Android creates the folders on first use), so do that off the main thread.
 */
class DataFiles(
    val filesDir: File,
    /** Excluded from every kind of Android backup. */
    val noBackupDir: File,
    val cacheDir: File,
    val databaseFile: File,
) {
    val mediaDir: File get() = File(filesDir, MediaPaths.MEDIA_DIR)
    val logsDir: File get() = File(filesDir, "logs")
    val crashDir: File get() = File(logsDir, "crash")

    /** Same path as in M1-S1, so the saved theme carries over. */
    val settingsFile: File get() = File(filesDir, "datastore/${AppSettings.FILE_NAME}")
    val deviceSettingsFile: File get() = File(filesDir, "datastore/${DeviceSettings.FILE_NAME}")

    /** The app's own copies of recent backups, so a damaged database can be restored without a chosen folder. */
    val localBackupsDir: File get() = File(noBackupDir, "backups")

    /** A restore waiting for the next start: [stagedDatabase], [stagedSettings], and [pendingRestoreMarker] last. */
    val restoreDir: File get() = File(noBackupDir, "restore")
    val stagedDatabase: File get() = File(restoreDir, "staged.db")
    val stagedSettings: File get() = File(restoreDir, "settings.json")
    val pendingRestoreMarker: File get() = File(restoreDir, "pending")

    /** Damaged or replaced databases, kept rather than deleted. */
    val damagedDir: File get() = File(noBackupDir, "damaged")

    val tempDir: File get() = File(cacheDir, "data-tmp")

    companion object {
        fun from(context: Context) = DataFiles(
            filesDir = context.filesDir,
            noBackupDir = context.noBackupFilesDir,
            cacheDir = context.cacheDir,
            databaseFile = context.getDatabasePath(PodDatabase.FILE_NAME),
        )

        /** One folder holding everything; for tests. */
        fun under(root: File) = DataFiles(
            filesDir = File(root, "files"),
            noBackupDir = File(root, "no_backup"),
            cacheDir = File(root, "cache"),
            databaseFile = File(root, "databases/${PodDatabase.FILE_NAME}"),
        )
    }
}
