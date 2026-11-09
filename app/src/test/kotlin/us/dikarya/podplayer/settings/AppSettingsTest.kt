/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.settings

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
