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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import us.dikarya.podplayer.ui.show.TAG_SHOW_AUTO_DOWNLOAD
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import us.dikarya.podplayer.ui.browse.awaitState
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

    // Each case in a window of its own width: Robolectric's default window is 320 dp, which clamped the wider cases.
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun unsubscribeFitsOnOneLineAt320Dp() = check(320)

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun unsubscribeFitsOnOneLineAt360DpTheOwnersPhone() = check(360)

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun unsubscribeFitsOnOneLineAt412Dp() = check(412)

    private fun check(width: Int) {
        val vm = ShowViewModel(env.deps, env.show("Alpha"))
        compose.setContent {
            PodplayerTheme(DarkTokens) {
                Box(Modifier.width(width.dp).testTag(TAG_HOST)) { ShowScreen(vm, onOpenEpisode = {}, onGone = {}) }
            }
        }
        val label = context.getString(R.string.action_unsubscribe)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        // CodeRabbit review (PR 2): a window narrower than [width] lays the screen out at the window's width instead.
        assertEquals("the screen is laid out $width dp wide", width.dp, compose.onNodeWithTag(TAG_HOST).getBoundsInRoot().let { it.right - it.left })
        // The label itself, not the button around it (a button is at least 48 dp tall for touch).
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        val bounds = node.getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        val textWidth = bounds.right - bounds.left
        // Squeezed, the label was one letter wide (and so many lines tall). "Unsubscribe" needs about 80 dp.
        assertTrue("at $width dp the label is only $textWidth wide and $height tall", textWidth > 70.dp)
        // Wide enough can still wrap: one line, nothing cut off (CodeRabbit review, PR 2).
        val layout = mutableListOf<TextLayoutResult>().also { results ->
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        }.single()
        assertEquals("at $width dp the label's lines", 1, layout.lineCount)
        assertFalse("at $width dp the label is cut short", layout.multiParagraph.didExceedMaxLines || layout.isLineEllipsized(0))
        // Measured directly, as in AccessibilityAuditUiTest: didOverflowWidth is also true for a short line laid out in
        // a wider paragraph.
        val line = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue("at $width dp the label's line is $line px in a ${layout.size.width} px box", line <= layout.size.width + 1)

        // M1-S9b: the "Download new episodes" row fits the width, is a full touch target, and its title isn't squeezed.
        val row = compose.onNodeWithTag(TAG_SHOW_AUTO_DOWNLOAD).getBoundsInRoot()
        assertTrue("at $width dp the row ends at ${row.right}", row.right <= width.dp)
        assertTrue("at $width dp the row is ${row.bottom - row.top} tall", row.bottom - row.top >= 48.dp)
        val title = compose.onNodeWithText(context.getString(R.string.show_auto_download), useUnmergedTree = true).getBoundsInRoot()
        assertTrue("at $width dp the title is ${title.right - title.left} wide", title.right - title.left > 120.dp)
        compose.onNodeWithTag(TAG_SHOW_AUTO_DOWNLOAD).assertIsOff().performClick()
        // Real time: the switch's write goes to Room on its own threads.
        awaitState(vm.state) { it.autoDownload }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val TAG_HOST = "show-layout-host"
    }
}
