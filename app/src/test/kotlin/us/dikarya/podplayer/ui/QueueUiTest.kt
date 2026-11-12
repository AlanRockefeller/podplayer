/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.queue.QueueScreen
import us.dikarya.podplayer.ui.queue.QueueViewModel
import us.dikarya.podplayer.ui.queue.handleTag
import us.dikarya.podplayer.ui.queue.rowTag
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** M1-S9 "Tests": queue reorder by dragging the handle and by the accessible Move up / Move down actions. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class QueueUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    private var opened: BrowseTestEnv? = null

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(CloseAfter { opened?.close() }).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root).also { opened = it } }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun dbOrder() = runBlocking { env.repos.queue.entries().first().map { it.episodeId } }

    /** Queues a, b, c, d and shows the screen once its rows are there. */
    private fun shown(): List<Long> {
        val ids = env.episodeIds(env.show("Alpha", episodes = 4))
        runBlocking { ids.forEach { env.repos.queue.add(it) } }
        val vm = QueueViewModel(env.deps)
        compose.setContent { PodplayerTheme(DarkTokens) { QueueScreen(vm, onOpenEpisode = {}) } }
        compose.waitInRealTime { compose.onAllNodesWithTag(rowTag(ids.last())).fetchSemanticsNodes().isNotEmpty() }
        return ids
    }

    @Test
    fun theAccessibleActionsMoveAnEpisodeUpAndDown() {
        val (a, b, c, d) = shown()
        compose.onNodeWithTag(rowTag(a)).performCustomAccessibilityActionWithLabel(context.getString(R.string.queue_move_down))
        compose.waitInRealTime { dbOrder() == listOf(b, a, c, d) }
        compose.waitInRealTime { compose.onAllNodesWithTag(rowTag(a)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(rowTag(d)).performCustomAccessibilityActionWithLabel(context.getString(R.string.queue_move_up))
        compose.waitInRealTime { dbOrder() == listOf(b, a, d, c) }
    }

    @Test
    fun theRowMenuOffersTheSameMovesPlayNextAndRemove() {
        val (a, b, c, d) = shown()
        compose.onNodeWithTag(menuTagFor(c)).performClick()
        compose.onNodeWithText(context.getString(R.string.queue_play_next)).performClick()
        compose.waitInRealTime { dbOrder() == listOf(c, a, b, d) }
        compose.onNodeWithTag(menuTagFor(b)).performClick()
        compose.onNodeWithText(context.getString(R.string.queue_remove)).performClick()
        compose.waitInRealTime { dbOrder() == listOf(c, a, d) }
    }

    @Test
    fun draggingTheHandleMovesTheEpisodeAndCommitsOnRelease() {
        val (a, b, c, d) = shown()
        val rowHeight = compose.onNodeWithTag(rowTag(a)).fetchSemanticsNode().size.height.toFloat()
        compose.onNodeWithTag(handleTag(a), useUnmergedTree = true).performTouchInput {
            down(center)
            // Past the touch slop, then two rows and a bit, in small steps like a finger.
            repeat(25) { moveBy(Offset(0f, rowHeight * 2.3f / 25)) }
            up()
        }
        compose.waitInRealTime { dbOrder() == listOf(b, c, a, d) }

        val height = compose.onNodeWithTag(rowTag(d)).fetchSemanticsNode().size.height.toFloat()
        compose.onNodeWithTag(handleTag(d), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(25) { moveBy(Offset(0f, -height * 3.4f / 25)) }
            up()
        }
        compose.waitInRealTime { dbOrder() == listOf(d, b, c, a) }
    }

    private fun menuTagFor(id: Long) = us.dikarya.podplayer.ui.queue.menuTag(id)
}
