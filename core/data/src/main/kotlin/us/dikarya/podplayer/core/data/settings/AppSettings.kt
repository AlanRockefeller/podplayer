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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode(val stored: String) {
    DARK("dark"),
    LIGHT("light"),
    SYSTEM("system"),
    ;

    companion object {
        /** SPEC §12: dark on first launch. */
        val DEFAULT = DARK

        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

/**
 * The owner's settings, in Preferences DataStore. Everything in this store goes into backups and comes back
 * on restore; settings that belong to this phone only live in [DeviceSettings]. A corrupt or unreadable file
 * falls back to the defaults.
 */
class AppSettings(private val store: DataStore<Preferences>) {
    private val data: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val themeMode: Flow<ThemeMode> = data
        .map { ThemeMode.fromStored(it.read<String>(THEME_MODE)) }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME_MODE] = mode.stored }
    }

    /** SPEC §9: downloads wait for an unmetered network (Wi-Fi) unless the owner turns this off. */
    val downloadsWifiOnly: Flow<Boolean> = data.map { it.read<Boolean>(DOWNLOADS_WIFI_ONLY) ?: true }.distinctUntilChanged()

    suspend fun setDownloadsWifiOnly(wifiOnly: Boolean) {
        store.edit { it[DOWNLOADS_WIFI_ONLY] = wifiOnly }
    }

    /**
     * SPEC §9: the most space downloads may take (default 10 GiB). Read values are clamped too, since a
     * restored backup can hold anything.
     */
    val storageCapBytes: Flow<Long> = data
        .map { (it.read<Long>(STORAGE_CAP_BYTES) ?: DEFAULT_STORAGE_CAP_BYTES).coerceIn(MIN_STORAGE_CAP_BYTES, MAX_STORAGE_CAP_BYTES) }
        .distinctUntilChanged()

    suspend fun setStorageCapBytes(bytes: Long) {
        store.edit { it[STORAGE_CAP_BYTES] = bytes.coerceIn(MIN_STORAGE_CAP_BYTES, MAX_STORAGE_CAP_BYTES) }
    }

    /** Every setting, for a backup. */
    suspend fun snapshot(): String = PreferencesSnapshot.encode(data.first())

    /** Replaces every setting with a backup's. Returns false, changing nothing, if [json] isn't a snapshot. */
    suspend fun restore(json: String): Boolean {
        val entries = PreferencesSnapshot.decode(json) ?: return false
        val restored = PreferencesSnapshot.toPreferences(entries)
        store.edit { prefs ->
            prefs.clear()
            prefs += restored
        }
        return true
    }

    companion object {
        /**
         * A value of the expected type, or null. A restored backup may store any type under any key, and
         * `Preferences.get` would throw on the unchecked cast.
         */
        private inline fun <reified T> Preferences.read(key: Preferences.Key<T>): T? = asMap()[key] as? T

        const val FILE_NAME = "settings.preferences_pb"
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DOWNLOADS_WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        private val STORAGE_CAP_BYTES = longPreferencesKey("storage_cap_bytes")

        private const val GIB = 1024L * 1024 * 1024
        const val DEFAULT_STORAGE_CAP_BYTES = 10 * GIB
        const val MIN_STORAGE_CAP_BYTES = GIB / 4
        const val MAX_STORAGE_CAP_BYTES = 1024 * GIB

        /** [produceFile] runs on the store's own dispatcher, never on the main thread. */
        fun create(scope: CoroutineScope, produceFile: () -> File): AppSettings = AppSettings(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
