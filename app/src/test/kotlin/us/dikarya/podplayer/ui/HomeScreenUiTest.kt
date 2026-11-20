/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.repo.KeepAsideResult
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.discover.PreviewOutcome
import us.dikarya.podplayer.ui.discover.rss
import us.dikarya.podplayer.ui.discover.success
import us.dikarya.podplayer.ui.home.HomeScreen
import us.dikarya.podplayer.ui.home.HomeViewModel
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Codex review, PR #1: with no show subscribed, Home hid the episode the owner was listening to. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HomeScreenUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = env.close()

    @Test
    fun withNoShowSubscribedAKeptAsideEpisodeIsStillOnContinueAboveTheInvitation() {
        runBlocking {
            env.answer = { success(rss("Aside", episodes = 1).toByteArray(), "application/rss+xml") }
            val parsed = env.deps.previewer.preview("https://aside.example.com/feed.xml", ownerTyped = false) as PreviewOutcome.Ready
            val aside = (env.repos.feeds.keepAside("https://aside.example.com/feed.xml", parsed.preview.feed) as KeepAsideResult.Stored).feedId
            env.repos.playback.savePosition(env.episodeIds(aside).single(), 60_000, 1_800_000)
        }
        compose.setContent { PodplayerTheme(DarkTokens) { HomeScreen(remember { HomeViewModel(env.deps) }, onOpenEpisode = {}, onOpenShow = {}, onDiscover = {}) } }
        val continueHeading = context.getString(R.string.home_continue)
        compose.waitInRealTime { compose.onAllNodesWithText(continueHeading).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.home_empty)).assertExists()
        compose.onNodeWithText(context.getString(R.string.find_shows)).assertExists()
    }
}
