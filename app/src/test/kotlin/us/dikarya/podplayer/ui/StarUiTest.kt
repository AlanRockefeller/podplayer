/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.LocalSnackbars
import us.dikarya.podplayer.ui.show.ShowScreen
import us.dikarya.podplayer.ui.show.ShowViewModel
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** SPEC §12: tapping the star stars at once (no dialog), and the Undo bar takes it back. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StarUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = env.close()

    private fun starred(id: Long) = runBlocking { env.repos.episodes.get(id)!!.starred }

    @Test
    fun aRowsStarStarsAtOnceAndUndoUnstars() {
        val feed = env.show("Alpha", episodes = 2)
        val newest = env.episodeIds(feed).first()
        val vm = ShowViewModel(env.deps, feed)
        val snackbars = SnackbarHostState()
        compose.setContent {
            PodplayerTheme(DarkTokens) {
                CompositionLocalProvider(LocalSnackbars provides snackbars) {
                    Box {
                        ShowScreen(vm, onOpenEpisode = {}, onGone = {})
                        SnackbarHost(snackbars)
                    }
                }
            }
        }
        val star = context.getString(R.string.action_star_episode, "Alpha 1")
        compose.waitInRealTime { compose.onAllNodesWithContentDescription(star).fetchSemanticsNodes().isNotEmpty() }
        // At this test's 320 dp the row's buttons sit on their own line under the title (ActionsBeside), below the fold.
        compose.onNodeWithContentDescription(star).performScrollTo().performClick()

        compose.waitInRealTime { starred(newest) }
        compose.waitInRealTime { compose.onAllNodesWithText(context.getString(R.string.undo)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.starred_done)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.action_unstar_episode, "Alpha 1")).assertExists()
        assertTrue(starred(newest))

        compose.onNodeWithText(context.getString(R.string.undo)).performClick()
        compose.waitInRealTime { !starred(newest) }
        compose.waitInRealTime { compose.onAllNodesWithContentDescription(star).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(starred(newest))
    }
}
