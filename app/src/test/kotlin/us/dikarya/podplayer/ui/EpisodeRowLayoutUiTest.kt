/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.show.ShowScreen
import us.dikarya.podplayer.ui.show.ShowViewModel
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/**
 * A show's episode rows on the owner's 360 dp phone: at the normal font size the four buttons stay beside the title
 * (the layout the owner uses every day); at 200% they move under it, so the title isn't squeezed to "True Crim…"
 * (seen on the phone, 2026-11-17).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpisodeRowLayoutUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = env.close()

    @Test
    fun atTheNormalFontSizeTheButtonsSitBesideTheTitle() {
        val (title, star) = layOut(fontScale = 1f)
        assertTrue("star at ${star.left}, title ends at ${title.right}", star.left >= title.right)
        assertTrue("star top ${star.top}, title bottom ${title.bottom}", star.top < title.bottom)
    }

    @Test
    fun at200PercentTheButtonsGoUnderTheTitleWhichKeepsItsRoom() {
        val (title, star) = layOut(fontScale = 2f)
        assertTrue("star top ${star.top}, title bottom ${title.bottom}", star.top >= title.bottom)
        assertTrue("title is only ${title.right - title.left} wide", title.right - title.left > 200.dp)
    }

    /** The bounds of episode 1's title and its star button. */
    private fun layOut(fontScale: Float): Pair<androidx.compose.ui.unit.DpRect, androidx.compose.ui.unit.DpRect> {
        val vm = ShowViewModel(env.deps, env.show("A Show With Several Words In Its Name", episodes = 1))
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PodplayerTheme(DarkTokens) {
                    Box(Modifier.width(360.dp)) { ShowScreen(vm, onOpenEpisode = {}, onGone = {}) }
                }
            }
        }
        val episode = "A Show With Several Words In Its Name 1"
        // At 200% the row starts below the fold, where the list hasn't composed it yet.
        val list = compose.onNode(hasScrollToIndexAction())
        // Real time: Room loads the show on its own threads (see waitInRealTime).
        compose.waitInRealTime(TIMEOUT) { compose.onAllNodes(hasText(episode)).fetchSemanticsNodes().isNotEmpty() || vm.state.value.episodes.isNotEmpty() }
        list.performScrollToNode(hasText(episode))
        compose.waitInRealTime(TIMEOUT) { compose.onAllNodesWithText(episode, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val star = compose.onNodeWithContentDescription(context.getString(R.string.action_star_episode, episode))
        star.performScrollTo()
        return compose.onNodeWithText(episode, useUnmergedTree = true).getBoundsInRoot() to star.getBoundsInRoot()
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
