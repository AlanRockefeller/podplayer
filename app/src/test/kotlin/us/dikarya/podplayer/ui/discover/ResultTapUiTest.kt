/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.CloseAfter
import us.dikarya.podplayer.ui.HostActivity
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.eventually
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme
import us.dikarya.podplayer.ui.waitInRealTime

/** Owner, 2026-11-18: tapping a search result opens its preview; its Subscribe button still subscribes at once. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ResultTapUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    // The library closes after the screen is gone, so no write of its view model resumes on a closed database.
    val rules: RuleChain = RuleChain.outerRule(tmp).around(CloseAfter { env.close() }).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @Test
    fun tappingAResultPreviewsItAndSubscribeStillSubscribes() {
        env.answer = { success("""{"results":[{"collectionName": "Found Show", "feedUrl": "https://found.example.com/feed.xml"}]}""".toByteArray(), "application/json") }
        val vm = DiscoverViewModel(env.deps)
        val previewed = mutableListOf<String>()
        compose.setContent { PodplayerTheme(DarkTokens) { DiscoverScreen(vm, onOpenShow = {}, onPreview = { previewed += it.feedUrl }) } }
        vm.onQueryChange("found")
        vm.submit()
        compose.waitInRealTime { compose.onAllNodesWithText("Found Show").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Found Show").performClick()
        assertEquals(listOf("https://found.example.com/feed.xml"), previewed)

        compose.onNodeWithText("Subscribe").performClick()
        eventually { env.refreshes.size == 1 }
        assertEquals("Subscribe doesn't open the preview", 1, previewed.size)
    }
}
