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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File by lazy { File(tmp.root, DeviceSettings.FILE_NAME) }

    /** Opens the store, runs [block], then closes it the way process death would. */
    private fun <T> withSettings(block: suspend (DeviceSettings) -> T): T = runBlocking {
        val job = SupervisorJob()
        try {
            block(DeviceSettings.create(CoroutineScope(Dispatchers.IO + job)) { file })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun nothingIsLoadedOnAFreshInstall() {
        assertNull(withSettings { it.currentEpisodeId() })
    }

    @Test
    fun theLoadedEpisodeSurvivesProcessDeath() {
        // Playback resumption after the service was killed reads this (SPEC §10).
        withSettings { it.setCurrentEpisodeId(42L) }
        assertEquals(42L, withSettings { it.currentEpisodeId() })
        withSettings { it.setCurrentEpisodeId(null) }
        assertNull(withSettings { it.currentEpisodeId() })
    }
}
