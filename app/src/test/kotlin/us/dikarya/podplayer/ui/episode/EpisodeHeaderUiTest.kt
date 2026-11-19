/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.episode

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.feed.FeedFormat
import us.dikarya.podplayer.core.feed.ParsedEnclosure
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.ParsedFeed
import us.dikarya.podplayer.ui.HostActivity
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.TAG_SHARE
import us.dikarya.podplayer.ui.browse.TAG_SHARE_COPY
import us.dikarya.podplayer.ui.browse.eventually
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme
import us.dikarya.podplayer.ui.waitInRealTime

/**
 * Owner's screenshot, 2026-11-18: a long title squeezed beside the artwork and star ran to 17 lines. The title now
 * takes the full width, a few lines at first with More; the share menu copies the episode's page.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so a long title really wraps.
class EpisodeHeaderUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private val longTitle = "Ship It Conversations: Ted Julian of Flux on the AI Code Visibility Gap, the Code Review Bottleneck, " +
        "Measuring What AI Changes, Who Owns AI-Generated Code in Production, Guardrails Before Loops, and Why Disruption " +
        "Is a Career Opportunity"

    private fun episode(link: String?): Long = runBlocking {
        val id = (env.repos.feeds.subscribe("https://feeds.example.com/ship-it.xml", ownerTyped = false) as SubscribeResult.Subscribed).feedId
        val item = ParsedEpisode(
            guid = "g1", title = longTitle, publishedAt = 1_790_000_000_000L,
            enclosure = ParsedEnclosure("https://cdn.example.com/1.mp3?token=SECRET", "audio/mpeg", 1000),
            durationMs = 2_400_000, notes = "<p>Notes</p>", link = link,
        )
        env.repos.feeds.applyRefresh(id, ParsedFeed(FeedFormat.Rss, title = "Ship It Weekly", link = "https://shipitweekly.example.com/", episodes = listOf(item)))
        env.episodeIds(id).single()
    }

    private fun show(episodeId: Long) {
        val vm = EpisodeViewModel(env.deps, episodeId)
        compose.setContent { PodplayerTheme(DarkTokens) { Box(Modifier.width(360.dp).fillMaxHeight()) { EpisodeScreen(vm, onOpenShow = {}) } } }
        compose.waitInRealTime { compose.onAllNodesWithTag(TAG_EPISODE_TITLE).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aLongTitleTakesTheFullWidthAndOpensWithMore() {
        show(episode(link = "https://shipitweekly.example.com/ep/85"))
        val bounds = compose.onNodeWithTag(TAG_EPISODE_TITLE).getBoundsInRoot()
        val wide = bounds.right - bounds.left
        val page = compose.onRoot().getBoundsInRoot().let { it.right - it.left }
        // The page's 16 dp padding on each side is all the title gives up (the artwork column took 112 dp more).
        assertTrue("the title spans the page, not a column beside the artwork: $wide of $page", wide >= page - 33.dp)
        val shut = bounds.bottom - bounds.top
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        val open = compose.onNodeWithTag(TAG_EPISODE_TITLE).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("More shows the rest", open > shut)
        compose.onNodeWithText("Less").performClick()
    }

    @Test
    fun copyLinkCopiesTheEpisodesPage() {
        show(episode(link = "https://shipitweekly.example.com/ep/85"))
        compose.onNodeWithTag(TAG_SHARE).performClick()
        compose.onNodeWithTag(TAG_SHARE_COPY).performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        eventually { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "https://shipitweekly.example.com/ep/85" }
    }

    @Test
    fun withoutAnEpisodePageItOffersTheShowsWebsite() {
        show(episode(link = null))
        compose.onNodeWithTag(TAG_SHARE).performClick()
        compose.onNodeWithText("Copy the show's website (no episode page)").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        eventually { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "https://shipitweekly.example.com/" }
        assertEquals(false, clipboard.primaryClip!!.getItemAt(0).text.contains("SECRET"))
    }
}
