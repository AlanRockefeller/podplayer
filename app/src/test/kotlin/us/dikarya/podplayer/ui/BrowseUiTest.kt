/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.discover.DiscoverScreen
import us.dikarya.podplayer.ui.discover.DiscoverViewModel
import us.dikarya.podplayer.ui.discover.TAG_FIELD
import us.dikarya.podplayer.ui.discover.TAG_PREVIEW
import us.dikarya.podplayer.ui.discover.rss
import us.dikarya.podplayer.ui.discover.success
import us.dikarya.podplayer.ui.player.MiniPlayerBar
import us.dikarya.podplayer.ui.player.MiniPlayerViewModel
import us.dikarya.podplayer.ui.player.TAG_MINI_PLAYER
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** M1-S8 "Tests": subscribe by URL, and the mini-player appearing when playback starts. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BrowseUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    // The library closes after the screen is gone, so no write of its view model resumes on a closed database.
    val rules: RuleChain = RuleChain.outerRule(tmp).around(CloseAfter { env.close() }).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aTypedFeedUrlIsPreviewedThenSubscribedWithItsHostApproved() {
        env.answer = { success(rss("Typed Show", episodes = 2).toByteArray(), "application/rss+xml") }
        val vm = DiscoverViewModel(env.deps)
        var opened: Long? = null
        compose.setContent { PodplayerTheme(DarkTokens) { DiscoverScreen(vm, onOpenShow = { opened = it }) } }

        compose.onNodeWithTag(TAG_FIELD).performTextInput("http://192.168.1.20:8000/feed.xml")
        compose.onNodeWithText(context.getString(R.string.discover_preview)).performClick()
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTag(TAG_PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Typed Show").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.not_secure_feed)).performScrollTo().assertIsDisplayed()
        assertTrue("nothing is subscribed by a preview", runBlocking { env.repos.feeds.allFeedIds() }.isEmpty())

        compose.onNodeWithText(context.getString(R.string.action_subscribe)).performScrollTo().performClick()
        compose.waitInRealTime(TIMEOUT) { opened != null }
        val feed = runBlocking { env.repos.feeds.get(opened!!) }!!
        assertEquals("http://192.168.1.20:8000/feed.xml", feed.url)
        assertEquals("the owner typed it, so its host is approved (SPEC §3)", "192.168.1.20", feed.ownerApprovedHost)
    }

    @Test
    fun theMiniPlayerAppearsWhenPlaybackStartsAndSendsCommands() {
        val feed = env.show("Alpha", episodes = 1)
        val episode = env.episodeIds(feed).single()
        val vm = MiniPlayerViewModel(env.deps)
        var opened: Long? = null
        compose.setContent { PodplayerTheme(DarkTokens) { MiniPlayerBar(vm, onOpen = { opened = it }) } }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_MINI_PLAYER).assertDoesNotExist()

        // The session starts playing (from anywhere: a row, the lock screen, the car).
        env.playback.playEpisode(episode)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTag(TAG_MINI_PLAYER).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Episode $episode").assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.action_pause)).performClick()
        assertEquals("pause", env.playback.commands.last())
        compose.onNodeWithContentDescription(context.getString(R.string.action_play)).assertIsDisplayed()

        compose.onNodeWithText("Episode $episode").performClick()
        assertEquals(episode, opened)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
