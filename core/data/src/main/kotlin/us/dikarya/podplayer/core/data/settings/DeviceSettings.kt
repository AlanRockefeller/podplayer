/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Why the last backup didn't fully work. Settings turns it into a plain message. */
enum class BackupFailure {
    /** The owner's folder couldn't be written (permission lost, folder deleted, storage full). */
    FolderUnreachable,

    /** The database couldn't be copied. */
    SnapshotFailed,

    /** No copy could be written anywhere. */
    WriteFailed,
}

/** What Settings shows about backups. */
data class BackupStatus(
    /** The Storage Access Framework tree the owner picked, or null. */
    val folderUri: String? = null,
    val folderName: String? = null,
    val lastBackupAt: Long? = null,
    /** Null after a good backup. */
    val lastFailure: BackupFailure? = null,
)

/**
 * Settings that belong to this phone and are never backed up or restored: the backup folder (its permission
 * is per device), backup bookkeeping, the device id that tags episode actions for sync, and log bookkeeping.
 */
class DeviceSettings(private val store: DataStore<Preferences>) {
    private val data: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val backupStatus: Flow<BackupStatus> = data.map { prefs ->
        val failure = prefs[LAST_BACKUP_ERROR]?.let { name -> BackupFailure.entries.firstOrNull { it.name == name } }
        BackupStatus(prefs[FOLDER_URI], prefs[FOLDER_NAME], prefs[LAST_BACKUP_AT], failure)
    }.distinctUntilChanged()

    suspend fun backupStatusNow(): BackupStatus = backupStatus.first()

    suspend fun setBackupFolder(uri: String?, name: String?) {
        store.edit {
            if (uri == null) it.remove(FOLDER_URI) else it[FOLDER_URI] = uri
            if (name == null) it.remove(FOLDER_NAME) else it[FOLDER_NAME] = name
            it.remove(LAST_BACKUP_ERROR)
        }
    }

    /** [at]: when a copy was last written somewhere (null: unchanged). [failure]: null clears it. */
    suspend fun recordBackup(at: Long?, failure: BackupFailure?) {
        store.edit {
            if (at != null) it[LAST_BACKUP_AT] = at
            if (failure == null) it.remove(LAST_BACKUP_ERROR) else it[LAST_BACKUP_ERROR] = failure.name
        }
    }

    /** A random id for this installation, made once. */
    suspend fun deviceId(): String {
        data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it } }
        return id
    }

    suspend fun lastExitSeenAt(): Long = data.first()[LAST_EXIT_SEEN] ?: 0L

    suspend fun setLastExitSeenAt(at: Long) {
        store.edit { it[LAST_EXIT_SEEN] = at }
    }

    /**
     * The episode loaded in the player on this phone, for playback resumption after the service or the
     * process died (SPEC §10). Device-only: a restore on another phone starts from the queue instead.
     */
    suspend fun currentEpisodeId(): Long? = data.first()[CURRENT_EPISODE]

    suspend fun setCurrentEpisodeId(id: Long?) {
        store.edit { if (id == null) it.remove(CURRENT_EPISODE) else it[CURRENT_EPISODE] = id }
    }

    /**
     * When auto-downloads last stopped because downloads reached the storage limit (SPEC §9), or null while they
     * run. Device-only: storage belongs to this phone.
     */
    val autoDownloadStoppedAt: Flow<Long?> = data.map { it[AUTO_DOWNLOAD_STOPPED_AT] }.distinctUntilChanged()

    suspend fun setAutoDownloadStoppedAt(at: Long?) {
        store.edit { if (at == null) it.remove(AUTO_DOWNLOAD_STOPPED_AT) else it[AUTO_DOWNLOAD_STOPPED_AT] = at }
    }

    companion object {
        private val AUTO_DOWNLOAD_STOPPED_AT = longPreferencesKey("auto_download_stopped_at")
        const val FILE_NAME = "device.preferences_pb"
        private val CURRENT_EPISODE = longPreferencesKey("current_episode_id")
        private val FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        private val FOLDER_NAME = stringPreferencesKey("backup_folder_name")
        private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        private val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val LAST_EXIT_SEEN = longPreferencesKey("last_exit_seen_at")

        fun create(scope: CoroutineScope, produceFile: () -> File): DeviceSettings = DeviceSettings(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
