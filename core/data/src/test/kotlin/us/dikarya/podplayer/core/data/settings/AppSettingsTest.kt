/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.settings

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.dikarya.podplayer.core.model.PlaybackSettings

class AppSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File by lazy { File(tmp.root, "settings.preferences_pb") }

    /** Opens the store, runs [block], then closes it the way process death would. */
    private fun <T> withSettings(block: suspend (AppSettings) -> T): T = runBlocking {
        val job = SupervisorJob()
        try {
            block(AppSettings.create(CoroutineScope(Dispatchers.IO + job)) { file })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun freshInstallIsDark() {
        assertEquals(ThemeMode.DARK, withSettings { it.themeMode.first() })
    }

    @Test
    fun defaultIsDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.DEFAULT)
    }

    @Test
    fun choiceIsReadBackInTheSameSession() {
        val mode = withSettings {
            it.setThemeMode(ThemeMode.SYSTEM)
            it.themeMode.first()
        }
        assertEquals(ThemeMode.SYSTEM, mode)
    }

    @Test
    fun lightPersistsAcrossRestart() {
        withSettings { it.setThemeMode(ThemeMode.LIGHT) }
        assertEquals(ThemeMode.LIGHT, withSettings { it.themeMode.first() })
    }

    @Test
    fun corruptFileFallsBackToDarkAndCanBeWrittenAgain() {
        file.writeText("this is not a protocol buffer")
        assertEquals(ThemeMode.DARK, withSettings { it.themeMode.first() })
        withSettings { it.setThemeMode(ThemeMode.LIGHT) }
        assertEquals(ThemeMode.LIGHT, withSettings { it.themeMode.first() })
    }

    @Test
    fun unknownStoredValueFallsBackToDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("sepia"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored(null))
    }

    @Test
    fun storedValuesRoundTrip() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStored(mode.stored))
    }

    @Test
    fun snapshotRestoresIntoAnotherStore() {
        val snapshot = withSettings {
            it.setThemeMode(ThemeMode.LIGHT)
            it.snapshot()
        }
        withSettings { it.setThemeMode(ThemeMode.SYSTEM) }
        assertTrue(withSettings { it.restore(snapshot) })
        assertEquals(ThemeMode.LIGHT, withSettings { it.themeMode.first() })
    }

    @Test
    fun downloadSettingsDefaultToWifiOnlyAndTenGib() {
        assertEquals(true, withSettings { it.downloadsWifiOnly.first() })
        assertEquals(10L * 1024 * 1024 * 1024, withSettings { it.storageCapBytes.first() })
    }

    @Test
    fun downloadSettingsPersistAndTravelWithABackup() {
        val snapshot = withSettings {
            it.setDownloadsWifiOnly(false)
            it.setStorageCapBytes(3L * 1024 * 1024 * 1024)
            it.snapshot()
        }
        assertEquals(false, withSettings { it.downloadsWifiOnly.first() })
        withSettings {
            it.setDownloadsWifiOnly(true)
            it.setStorageCapBytes(AppSettings.DEFAULT_STORAGE_CAP_BYTES)
        }
        assertTrue(withSettings { it.restore(snapshot) })
        assertEquals(false, withSettings { it.downloadsWifiOnly.first() })
        assertEquals(3L * 1024 * 1024 * 1024, withSettings { it.storageCapBytes.first() })
    }

    @Test
    fun theStorageCapIsKeptWithinSaneBounds() {
        assertEquals(AppSettings.MIN_STORAGE_CAP_BYTES, withSettings {
            it.setStorageCapBytes(-5)
            it.storageCapBytes.first()
        })
        assertEquals(AppSettings.MAX_STORAGE_CAP_BYTES, withSettings {
            it.setStorageCapBytes(Long.MAX_VALUE)
            it.storageCapBytes.first()
        })
    }

    @Test
    fun aValueOfTheWrongTypeReadsAsTheDefault() {
        // A hand-made backup can store any type under any key; reading must not throw.
        val json = """{"format":1,"entries":[
            {"k":"downloads_wifi_only","t":"string","v":"no"},
            {"k":"storage_cap_bytes","t":"bool","v":true},
            {"k":"theme_mode","t":"int","v":3}]}"""
        assertTrue(withSettings { it.restore(json) })
        assertEquals(true, withSettings { it.downloadsWifiOnly.first() })
        assertEquals(AppSettings.DEFAULT_STORAGE_CAP_BYTES, withSettings { it.storageCapBytes.first() })
        assertEquals(ThemeMode.DARK, withSettings { it.themeMode.first() })
    }

    @Test
    fun playbackDefaultsFollowSpec10() {
        val settings = withSettings { it.playback.first() }
        assertEquals(PlaybackSettings(), settings)
        assertEquals(1.0f, settings.speed)
        assertEquals(15, settings.skipBackSeconds)
        assertEquals(30, settings.skipForwardSeconds)
        assertFalse("rewind after a long pause is off by default (SPEC §10)", settings.rewindAfterPause)
    }

    @Test
    fun playbackSettingsPersistAcrossRestart() {
        withSettings {
            it.setPlaybackSpeed(1.5f)
            it.setSkipSeconds(back = 10, forward = 45)
            it.setRewindAfterPause(enabled = true, rewindSeconds = 20, afterPauseMinutes = 30)
        }
        assertEquals(
            PlaybackSettings(speed = 1.5f, skipBackSeconds = 10, skipForwardSeconds = 45, rewindAfterPause = true, rewindSeconds = 20, rewindAfterPauseMinutes = 30),
            withSettings { it.playback.first() },
        )
    }

    @Test
    fun playbackSettingsAreClampedOnWriteAndOnRead() {
        val written = withSettings {
            it.setPlaybackSpeed(40f)
            it.setSkipSeconds(back = 0, forward = 100_000)
            it.setRewindAfterPause(enabled = true, rewindSeconds = -3, afterPauseMinutes = Int.MAX_VALUE)
            it.playback.first()
        }
        assertEquals(PlaybackSettings.MAX_SPEED, written.speed)
        assertEquals(PlaybackSettings.MIN_SKIP_SECONDS, written.skipBackSeconds)
        assertEquals(PlaybackSettings.MAX_SKIP_SECONDS, written.skipForwardSeconds)
        assertEquals(PlaybackSettings.MIN_REWIND_SECONDS, written.rewindSeconds)
        assertEquals(PlaybackSettings.MAX_REWIND_AFTER_PAUSE_MINUTES, written.rewindAfterPauseMinutes)

        // A hand-made backup bypasses the setters; reading clamps again, and NaN is the default.
        val json = """{"format":1,"entries":[
            {"k":"playback_speed","t":"float","v":0.01},
            {"k":"skip_back_seconds","t":"int","v":-50},
            {"k":"skip_forward_seconds","t":"int","v":99999}]}"""
        assertTrue(withSettings { it.restore(json) })
        val read = withSettings { it.playback.first() }
        assertEquals(PlaybackSettings.MIN_SPEED, read.speed)
        assertEquals(PlaybackSettings.MIN_SKIP_SECONDS, read.skipBackSeconds)
        assertEquals(PlaybackSettings.MAX_SKIP_SECONDS, read.skipForwardSeconds)
        assertEquals(1.0f, PlaybackSettings.clampSpeed(Float.NaN))
    }

    @Test
    fun playbackSettingsOfTheWrongTypeReadAsDefaults() {
        val json = """{"format":1,"entries":[
            {"k":"playback_speed","t":"string","v":"fast"},
            {"k":"skip_back_seconds","t":"bool","v":true},
            {"k":"rewind_after_pause","t":"int","v":1}]}"""
        assertTrue(withSettings { it.restore(json) })
        assertEquals(PlaybackSettings(), withSettings { it.playback.first() })
    }

    @Test
    fun aBadSnapshotChangesNothing() {
        withSettings { it.setThemeMode(ThemeMode.LIGHT) }
        assertFalse(withSettings { it.restore("{\"format\": 99}") })
        assertFalse(withSettings { it.restore("garbage") })
        assertEquals(ThemeMode.LIGHT, withSettings { it.themeMode.first() })
    }
}
