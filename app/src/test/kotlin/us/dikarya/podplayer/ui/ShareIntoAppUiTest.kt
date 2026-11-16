/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import androidx.compose.ui.test.assertTextContains
import us.dikarya.podplayer.PodplayerApp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.ui.discover.importlist.TAG_IMPORT_FIELD
import us.dikarya.podplayer.ui.settings.SettingsTestEnv
import us.dikarya.podplayer.ui.shell.AppShell
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/**
 * SPEC §12: a list of shows shared into the app opens Discover › Import a list with the list filled in. On the
 * owner's phone (2026-11-15) a share opened the app on Home and the importer stayed empty; this drives the whole
 * shell the way MainActivity does.
 */
@RunWith(AndroidJUnit4::class)
// The real Application: the shell's data-state dialogs read the app graph.
@Config(application = PodplayerApp::class)
class ShareIntoAppUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { SettingsTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun shell() = compose.setContent {
        PodplayerTheme(DarkTokens) {
            AppShell(themeMode = ThemeMode.DARK, onThemeModeChange = {}, browse = env.browse.deps, settings = env.deps)
        }
    }

    @Test
    fun aListSharedBeforeTheAppStartsOpensTheImporterFilledIn() {
        env.browse.deps.sharedText.value = "Hard Fork\nThe Daily"
        shell()
        compose.waitInRealTime(TIMEOUT) { compose.onAllNodesWithTag(TAG_IMPORT_FIELD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_IMPORT_FIELD).assertTextContains("Hard Fork", substring = true)
    }

    @Test
    fun aListSharedWhileTheAppIsOpenOpensTheImporterFilledIn() {
        shell()
        compose.waitForIdle()
        env.browse.deps.sharedText.value = "Hard Fork"
        compose.waitInRealTime(TIMEOUT) { compose.onAllNodesWithTag(TAG_IMPORT_FIELD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_IMPORT_FIELD).assertTextContains("Hard Fork", substring = true)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
