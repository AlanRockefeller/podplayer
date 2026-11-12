/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.ui.settings.LogsScreen
import us.dikarya.podplayer.ui.settings.LogsViewModel
import us.dikarya.podplayer.ui.settings.SettingsScreen
import us.dikarya.podplayer.ui.settings.SettingsTestEnv
import us.dikarya.podplayer.ui.settings.SettingsViewModel
import us.dikarya.podplayer.ui.settings.TAG_SETTINGS_LIST
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** M1-S9: every M1 setting is on the screen; log export happens only on Export, through the share sheet. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    private var opened: SettingsTestEnv? = null

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(CloseAfter { opened?.close() }).around(compose)

    private val env by lazy { SettingsTestEnv(tmp.root).also { opened = it } }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun everySectionAndSettingIsReachable() {
        val vm = SettingsViewModel(env.deps)
        compose.setContent {
            PodplayerTheme(DarkTokens) {
                SettingsScreen(vm, ThemeMode.DARK, onThemeModeChange = {}, onOpenLogs = {}, onOpenLicenses = {}, backup = {})
            }
        }
        compose.waitInRealTime { !vm.state.value.loading }
        val labels = listOf(
            R.string.settings_appearance, R.string.settings_playback, R.string.settings_speed, R.string.settings_skip_back,
            R.string.settings_skip_forward, R.string.settings_rewind_after_pause, R.string.settings_downloads,
            R.string.settings_wifi_only, R.string.settings_storage_cap, R.string.settings_logs, R.string.settings_about,
            R.string.settings_licenses,
        )
        for (label in labels) {
            compose.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasText(app.getString(label)))
            compose.onNodeWithText(app.getString(label)).assertIsDisplayed()
        }
    }

    @Test
    fun theWifiOnlySwitchGoesThroughTheJobs() {
        val vm = SettingsViewModel(env.deps)
        compose.setContent {
            PodplayerTheme(DarkTokens) {
                SettingsScreen(vm, ThemeMode.DARK, onThemeModeChange = {}, onOpenLogs = {}, onOpenLicenses = {}, backup = {})
            }
        }
        compose.waitInRealTime { !vm.state.value.loading }
        compose.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasText(app.getString(R.string.settings_wifi_only)))
        compose.onNodeWithText(app.getString(R.string.settings_wifi_only)).performClick()
        compose.waitInRealTime { env.wifiOnlyChanges == listOf(false) }
        // The fake records the call first, then stores it: wait for the stored value.
        compose.waitInRealTime { !runBlocking { env.deps.settings.downloadsWifiOnly.first() } }
        compose.waitInRealTime { !vm.state.value.wifiOnly }
    }

    @Test
    fun logsAreSharedOnlyAfterExportIsTappedAndTheSharedFileIsRedacted() {
        env.browse.log.i("Refresh", "Fetched https://feeds.example.com/private.xml?token=SECRETTOKEN")
        val vm = LogsViewModel(env.deps)
        compose.setContent { PodplayerTheme(DarkTokens) { LogsScreen(vm) } }
        compose.waitInRealTime { vm.state.value.entries.isNotEmpty() }
        compose.onNodeWithText("Fetched https://feeds.example.com/private.xml", substring = true).assertIsDisplayed()
        assertNull("nothing shared just by looking", shadowOf(app).peekNextStartedActivity())
        assertTrue(env.exportDir.listFiles().isNullOrEmpty())

        compose.onNodeWithText(app.getString(R.string.logs_export)).performClick()
        compose.waitInRealTime { shadowOf(app).peekNextStartedActivity() != null }
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.logexport", uri.authority)

        val exported = env.exportDir.listFiles()!!.single()
        val text = exported.readText()
        assertFalse(text, "SECRETTOKEN" in text)
        assertTrue(text, "https://feeds.example.com/private.xml" in text)
    }
}
