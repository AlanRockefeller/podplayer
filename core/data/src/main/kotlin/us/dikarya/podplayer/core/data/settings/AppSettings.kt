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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import us.dikarya.podplayer.core.model.PlaybackSettings
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

/** How Library shows the subscriptions (SPEC §12: a grid with a list toggle). Stored by name. */
enum class LibraryLayout {
    Grid,
    List,
    ;

    companion object {
        fun fromStored(value: String?): LibraryLayout = entries.firstOrNull { it.name == value } ?: Grid
    }
}

/** Library's order. Stored by name. */
enum class LibrarySort {
    /** A to Z. */
    Title,

    /** The show with the newest episode first. */
    NewestEpisode,

    /** Most recently subscribed first. */
    Subscribed,
    ;

    companion object {
        fun fromStored(value: String?): LibrarySort = entries.firstOrNull { it.name == value } ?: Title
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

    val libraryLayout: Flow<LibraryLayout> = data.map { LibraryLayout.fromStored(it.read<String>(LIBRARY_LAYOUT)) }.distinctUntilChanged()

    suspend fun setLibraryLayout(layout: LibraryLayout) {
        store.edit { it[LIBRARY_LAYOUT] = layout.name }
    }

    val librarySort: Flow<LibrarySort> = data.map { LibrarySort.fromStored(it.read<String>(LIBRARY_SORT)) }.distinctUntilChanged()

    suspend fun setLibrarySort(sort: LibrarySort) {
        store.edit { it[LIBRARY_SORT] = sort.name }
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

    /** SPEC §10: global speed, skip intervals and rewind-after-pause, clamped on read. */
    val playback: Flow<PlaybackSettings> = data
        .map { prefs ->
            val default = PlaybackSettings()
            PlaybackSettings(
                speed = prefs.read<Float>(PLAYBACK_SPEED) ?: default.speed,
                skipBackSeconds = prefs.read<Int>(SKIP_BACK_SECONDS) ?: default.skipBackSeconds,
                skipForwardSeconds = prefs.read<Int>(SKIP_FORWARD_SECONDS) ?: default.skipForwardSeconds,
                rewindAfterPause = prefs.read<Boolean>(REWIND_AFTER_PAUSE) ?: default.rewindAfterPause,
                rewindSeconds = prefs.read<Int>(REWIND_SECONDS) ?: default.rewindSeconds,
                rewindAfterPauseMinutes = prefs.read<Int>(REWIND_AFTER_PAUSE_MINUTES) ?: default.rewindAfterPauseMinutes,
            ).normalized()
        }
        .distinctUntilChanged()

    suspend fun setPlaybackSpeed(speed: Float) {
        store.edit { it[PLAYBACK_SPEED] = PlaybackSettings.clampSpeed(speed) }
    }

    suspend fun setSkipSeconds(back: Int, forward: Int) {
        val clamped = PlaybackSettings(skipBackSeconds = back, skipForwardSeconds = forward).normalized()
        store.edit {
            it[SKIP_BACK_SECONDS] = clamped.skipBackSeconds
            it[SKIP_FORWARD_SECONDS] = clamped.skipForwardSeconds
        }
    }

    suspend fun setRewindAfterPause(enabled: Boolean, rewindSeconds: Int, afterPauseMinutes: Int) {
        val clamped = PlaybackSettings(rewindSeconds = rewindSeconds, rewindAfterPauseMinutes = afterPauseMinutes).normalized()
        store.edit {
            it[REWIND_AFTER_PAUSE] = enabled
            it[REWIND_SECONDS] = clamped.rewindSeconds
            it[REWIND_AFTER_PAUSE_MINUTES] = clamped.rewindAfterPauseMinutes
        }
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
        private val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
        private val LIBRARY_SORT = stringPreferencesKey("library_sort")
        private val DOWNLOADS_WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        private val STORAGE_CAP_BYTES = longPreferencesKey("storage_cap_bytes")
        private val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        private val SKIP_BACK_SECONDS = intPreferencesKey("skip_back_seconds")
        private val SKIP_FORWARD_SECONDS = intPreferencesKey("skip_forward_seconds")
        private val REWIND_AFTER_PAUSE = booleanPreferencesKey("rewind_after_pause")
        private val REWIND_SECONDS = intPreferencesKey("rewind_seconds")
        private val REWIND_AFTER_PAUSE_MINUTES = intPreferencesKey("rewind_after_pause_minutes")

        /**
         * Decimal gigabytes, the unit Android shows sizes in ("35 GB free"): a 10 GB limit reads "10 GB", not the
         * "11 GB" a binary 10 GiB showed on the owner's phone.
         */
        const val GB = 1_000_000_000L
        const val DEFAULT_STORAGE_CAP_BYTES = 10 * GB
        const val MIN_STORAGE_CAP_BYTES = GB / 4
        const val MAX_STORAGE_CAP_BYTES = 1000 * GB

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
