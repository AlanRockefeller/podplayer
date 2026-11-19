/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.HostActivity
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.eventually
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme
import us.dikarya.podplayer.ui.waitInRealTime

/** Owner, 2026-11-18: listen to a show from its preview without subscribing (SPEC §12). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PreviewPlayUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun episodeId(guid: String): Long = runBlocking {
        val feed = env.repos.feeds.allFeeds().first().single()
        env.repos.episodes.forFeed(feed.id).first().single { it.guid == guid }.id
    }

    @Test
    fun theEpisodesPlayButtonPlaysItAndTappingTheRowOpensIt() {
        env.answer = { success(rss("Found Show", episodes = 3).toByteArray(), "application/rss+xml") }
        val vm = ShowPreviewViewModel(env.deps, "https://found.example.com/feed.xml")
        val opened = mutableListOf<Long>()
        compose.setContent { PodplayerTheme(DarkTokens) { ShowPreviewScreen(vm, artworkUrl = null, onOpenShow = {}, onOpenEpisode = { opened += it }) } }
        compose.waitInRealTime { compose.onAllNodesWithTag("${TAG_PREVIEW_PLAY}0").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Play Episode 2").performClick()
        eventually { env.playback.commands.isNotEmpty() }
        assertEquals(listOf("play ${episodeId("g2")}"), env.playback.commands)

        compose.onNodeWithTag("${TAG_PREVIEW_EPISODE}0").performClick()
        compose.waitInRealTime { opened.isNotEmpty() }
        assertEquals(listOf(episodeId("g3")), opened)
        assertTrue("still not subscribed", runBlocking { env.repos.feeds.feeds().first().isEmpty() })
    }
}
