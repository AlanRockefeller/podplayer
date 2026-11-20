/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.browse.ActionsBeside
import us.dikarya.podplayer.ui.browse.ArtworkBeside

/** CodeRabbit review, PR #1: the app supports right-to-left, so "at the end" and "at the start" must mirror. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ActionsBesideUiTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(HostActivity()).around(compose)

    @Test
    fun rightToLeftPutsTheActionsBesideTheTextOnTheLeft() {
        val (main, actions) = actionsBeside(LayoutDirection.Rtl, minMain = 100.dp)
        assertTrue("actions end at ${actions.right}, main starts at ${main.left}", actions.right <= main.left)
    }

    @Test
    fun rightToLeftPutsStackedActionsAtTheLeftUnderTheText() {
        val (main, actions) = actionsBeside(LayoutDirection.Rtl, minMain = 290.dp)
        assertTrue("actions top ${actions.top}, main bottom ${main.bottom}", actions.top >= main.bottom)
        assertTrue("actions at ${actions.left}", actions.left < 1.dp)
    }

    @Test
    fun leftToRightIsUnchanged() {
        val (main, actions) = actionsBeside(LayoutDirection.Ltr, minMain = 100.dp)
        assertTrue("actions start at ${actions.left}, main ends at ${main.right}", actions.left >= main.right)
    }

    @Test
    fun rightToLeftPutsTheArtworkOnTheRight() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(300.dp)) {
                    ArtworkBeside(
                        minTextWidth = 100.dp,
                        artwork = { Box(Modifier.size(60.dp).testTag("artwork")) },
                        text = { Box(Modifier.size(20.dp).testTag("text")) },
                    )
                }
            }
        }
        val artwork = compose.onNodeWithTag("artwork").getBoundsInRoot()
        val text = compose.onNodeWithTag("text").getBoundsInRoot()
        assertTrue("artwork starts at ${artwork.left}, text ends at ${text.right}", artwork.left >= text.right)
    }

    /** A 300 dp wide [ActionsBeside] with a 60 dp action; returns the bounds of the main part and the action. */
    private fun actionsBeside(direction: LayoutDirection, minMain: Dp): Pair<DpRect, DpRect> {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.width(300.dp)) {
                    ActionsBeside(
                        minMainWidth = minMain,
                        main = { Box(Modifier.size(20.dp).testTag("main")) },
                        actions = { Box(Modifier.size(60.dp).testTag("actions")) },
                    )
                }
            }
        }
        return compose.onNodeWithTag("main").getBoundsInRoot() to compose.onNodeWithTag("actions").getBoundsInRoot()
    }
}
