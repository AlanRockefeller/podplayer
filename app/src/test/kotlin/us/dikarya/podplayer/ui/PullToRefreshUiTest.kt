/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.TAG_PULL_TO_REFRESH
import us.dikarya.podplayer.ui.home.HomeScreen
import us.dikarya.podplayer.ui.home.HomeViewModel
import us.dikarya.podplayer.ui.library.LibraryScreen
import us.dikarya.podplayer.ui.library.LibraryViewModel
import us.dikarya.podplayer.ui.show.ShowScreen
import us.dikarya.podplayer.ui.show.ShowViewModel
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Owner, 2026-11-18: pulling down refreshes Home and Library (every show) and a show's page (that show). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PullToRefreshUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    @Test
    fun pullingDownOnHomeRefreshesEveryShow() = pull("Alpha 1") { HomeScreen(remember { HomeViewModel(env.deps) }, onOpenEpisode = {}, onOpenShow = {}, onDiscover = {}) }.let {
        assertEquals(listOf<Long?>(null), env.refreshes)
    }

    @Test
    fun pullingDownOnLibraryRefreshesEveryShow() = pull("Alpha") { LibraryScreen(remember { LibraryViewModel(env.deps) }, onOpenShow = {}, onDiscover = {}) }.let {
        assertEquals(listOf<Long?>(null), env.refreshes)
    }

    @Test
    fun pullingDownOnAShowRefreshesThatShow() {
        val feed = pull("Alpha 1") { feedId -> ShowScreen(remember { ShowViewModel(env.deps, feedId) }, onOpenEpisode = {}, onGone = {}) }
        assertEquals(listOf<Long?>(feed), env.refreshes)
    }

    /** One show, [content] on screen until [waitFor] shows, then a pull down. Returns the show's id. */
    private fun pull(waitFor: String, content: @Composable (Long) -> Unit): Long {
        val feed = env.show("Alpha", episodes = 2)
        env.refreshes.clear()
        compose.setContent { PodplayerTheme(DarkTokens) { content(feed) } }
        compose.waitInRealTime { compose.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_PULL_TO_REFRESH).performTouchInput { swipeDown() }
        compose.waitInRealTime { env.refreshes.isNotEmpty() }
        return feed
    }
}
