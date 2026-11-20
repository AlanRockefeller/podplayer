/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.library

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.settings.LibraryLayout
import us.dikarya.podplayer.core.data.settings.LibrarySort
import us.dikarya.podplayer.ui.CloseAfter
import us.dikarya.podplayer.ui.HostActivity
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme
import us.dikarya.podplayer.ui.waitInRealTime

/**
 * Found on the owner's phone (2026-11-17): changing the sort kept the grid's scroll position, so the new order's top
 * wasn't shown. A new sort starts at the top.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LibrarySortUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    // The library closes after the screen is gone, so no write of its view model resumes on a closed database.
    val rules: RuleChain = RuleChain.outerRule(tmp).around(CloseAfter { env.close() }).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @Test
    fun aNewSortStartsAtTheTopOfTheGrid() = aNewSortStartsAtTheTop(LibraryLayout.Grid)

    /** Codex review, PR #27: with List saved, waiting on the grid's scroll kept the list from ever scrolling. */
    @Test
    fun aNewSortStartsAtTheTopOfTheList() = aNewSortStartsAtTheTop(LibraryLayout.List)

    private fun aNewSortStartsAtTheTop(layout: LibraryLayout) {
        runBlocking {
            env.data.settings.setLibrarySort(LibrarySort.Title)
            env.data.settings.setLibraryLayout(layout)
        }
        repeat(SHOWS) { env.show("Show %02d".format(it), episodes = 1) }
        val vm = LibraryViewModel(env.deps)
        compose.setContent { PodplayerTheme(DarkTokens) { LibraryScreen(vm, onOpenShow = {}, onDiscover = {}) } }
        compose.waitInRealTime { compose.onAllNodesWithText("Show 00").fetchSemanticsNodes().isNotEmpty() }
        // The middle: the list keeps its place by the first visible show, which stays mid-list in either order.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(SHOWS / 2)
        compose.waitForIdle()

        // The most recently subscribed show comes first in the new order, and it's on screen.
        vm.setSort(LibrarySort.Subscribed)
        compose.waitInRealTime { compose.onAllNodesWithText("Show ${SHOWS - 1}").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Show ${SHOWS - 1}").assertIsDisplayed()
    }

    private companion object {
        const val SHOWS = 30
    }
}
