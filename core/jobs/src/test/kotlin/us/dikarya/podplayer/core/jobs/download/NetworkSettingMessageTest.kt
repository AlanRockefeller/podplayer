/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's phone showed "1 waiting downloads now use any network" in the app log (2026-11-12). */
class NetworkSettingMessageTest {
    @Test
    fun oneDownloadIsSingular() {
        assertEquals("1 waiting download now uses any network", Downloads.networkSettingMessage(1, wifiOnly = false))
        assertEquals("1 waiting download now waits for Wi-Fi", Downloads.networkSettingMessage(1, wifiOnly = true))
    }

    @Test
    fun severalDownloadsArePlural() {
        assertEquals("3 waiting downloads now use any network", Downloads.networkSettingMessage(3, wifiOnly = false))
        assertEquals("3 waiting downloads now wait for Wi-Fi", Downloads.networkSettingMessage(3, wifiOnly = true))
    }
}
