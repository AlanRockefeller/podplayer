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
        .map { ThemeMode.fromStored(it[THEME_MODE]) }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME_MODE] = mode.stored }
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
        const val FILE_NAME = "settings.preferences_pb"
        private val THEME_MODE = stringPreferencesKey("theme_mode")

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
