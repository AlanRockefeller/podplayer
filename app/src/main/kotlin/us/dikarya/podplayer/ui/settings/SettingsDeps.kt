/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import java.io.File
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.logs.LogExport
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.settings.AppSettings

/** What the settings and logs screens need, built once in `AppGraph` (manual DI, CLAUDE.md). */
class SettingsDeps(
    val settings: AppSettings,
    val repositories: suspend () -> Repositories?,
    /** Stores the Wi-Fi-only choice and moves waiting downloads to it (`Jobs.setDownloadsWifiOnly`). */
    val setWifiOnly: suspend (Boolean) -> Unit,
    /** Free bytes where downloads are stored, or null when it can't be read. */
    val freeBytes: () -> Long?,
    val logs: LogExport,
    /** Where the export file goes; the FileProvider shares only this folder (res/xml/log_export_paths.xml). */
    val exportDir: () -> File,
    val log: AppLog,
    val appVersion: String,
)
