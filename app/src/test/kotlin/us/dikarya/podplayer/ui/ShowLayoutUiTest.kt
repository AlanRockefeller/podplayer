/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * The owner's phone (360 dp wide) showed "Unsubscribe" one letter per line: the speed button and icons left it no
 * width. The show page's controls must fit a phone, each label on one line.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
// Real text measurement: in Robolectric's default graphics mode every label measures a few dp wide, so a squeezed
// label and a normal one look the same.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShowLayoutUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = env.close()

    @Test
    fun unsubscribeFitsOnOneLineAt320Dp() = check(320)

    @Test
    fun unsubscribeFitsOnOneLineAt360DpTheOwnersPhone() = check(360)

    @Test
    fun unsubscribeFitsOnOneLineAt412Dp() = check(412)

    private fun check(width: Int) {
        val vm = ShowViewModel(env.deps, env.show("Alpha"))
        compose.setContent {
            PodplayerTheme(DarkTokens) {
                Box(Modifier.width(width.dp)) { ShowScreen(vm, onOpenEpisode = {}, onGone = {}) }
            }
        }
        val label = context.getString(R.string.action_unsubscribe)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        // The label itself, not the button around it (a button is at least 48 dp tall for touch).
        val bounds = compose.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        val textWidth = bounds.right - bounds.left
        // Squeezed, the label was one letter wide (and so many lines tall). "Unsubscribe" needs about 80 dp.
        assertTrue("at $width dp the label is only $textWidth wide and $height tall", textWidth > 70.dp)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
