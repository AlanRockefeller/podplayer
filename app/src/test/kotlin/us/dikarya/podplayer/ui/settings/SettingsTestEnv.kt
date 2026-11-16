/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import us.dikarya.podplayer.core.data.logs.LogExport
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.ui.browse.BrowseTestEnv

/** [BrowseTestEnv] plus what the settings screens need; the Wi-Fi-only change is recorded. */
class SettingsTestEnv(val root: File) {
    val browse = BrowseTestEnv(root)
    val wifiOnlyChanges = mutableListOf<Boolean>()
    val autoWifiOnlyChanges = mutableListOf<Boolean>()
    val cleanUpChanges = mutableListOf<CleanUpSettings>()
    val crashDir = File(root, "app/files/logs/crash")
    /** Under the real cache folder: the FileProvider only shares files from there (res/xml/log_export_paths.xml). */
    val exportDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "exports")
    var freeBytes: Long? = 50L * 1024 * 1024 * 1024

    val deps = SettingsDeps(
        settings = browse.data.settings,
        repositories = { browse.data.awaitRepositories() },
        setWifiOnly = { wifiOnly ->
            wifiOnlyChanges += wifiOnly
            browse.data.settings.setDownloadsWifiOnly(wifiOnly)
        },
        setAutoDownloadWifiOnly = { wifiOnly ->
            autoWifiOnlyChanges += wifiOnly
            browse.data.settings.setAutoDownloadWifiOnly(wifiOnly)
        },
        setCleanUp = { cleanUp ->
            cleanUpChanges += cleanUp
            browse.data.settings.setCleanUp(cleanUp)
        },
        autoDownloadStoppedAt = browse.data.device.autoDownloadStoppedAt,
        freeBytes = { freeBytes },
        logs = LogExport(browse.log, { crashDir }, { browse.data.awaitRepositories() }, "1.0-test"),
        exportDir = { exportDir },
        log = browse.log,
        appVersion = "1.0-test",
    )

    fun close() = browse.close()
}
