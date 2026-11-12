/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.library

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.settings.LibraryLayout
import us.dikarya.podplayer.core.data.settings.LibrarySort
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState

/** SPEC §12 Library: subscriptions grid with a list toggle, and a sort. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LibraryViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    @Test
    fun showsSortByTitleNewestEpisodeOrSubscriptionAndTheChoicePersists() = runBlocking {
        // Subscribed in this order: Beta, alpha, Gamma. Beta has the newest episode, Gamma the oldest.
        env.show("Beta", publishedBase = 1_790_000_000_000L + 2 * BrowseTestEnv.DAY)
        env.show("alpha", publishedBase = 1_790_000_000_000L + BrowseTestEnv.DAY)
        env.show("Gamma", publishedBase = 1_790_000_000_000L)
        val vm = LibraryViewModel(env.deps)
        val byTitle = awaitState(vm.state) { it.shows.size == 3 }
        assertEquals("A to Z, ignoring case", listOf("alpha", "Beta", "Gamma"), byTitle.shows.map { it.feed.title })
        assertEquals(LibraryLayout.Grid, byTitle.layout)

        vm.setSort(LibrarySort.NewestEpisode)
        awaitState(vm.state) { s -> s.shows.map { it.feed.title } == listOf("Beta", "alpha", "Gamma") }
        vm.setSort(LibrarySort.Subscribed)
        awaitState(vm.state) { s -> s.shows.map { it.feed.title } == listOf("Gamma", "alpha", "Beta") }
        assertEquals(LibrarySort.Subscribed, env.data.settings.librarySort.first())

        vm.setLayout(LibraryLayout.List)
        awaitState(vm.state) { it.layout == LibraryLayout.List }
        assertEquals(LibraryLayout.List, env.data.settings.libraryLayout.first())
    }

    @Test
    fun refreshAllAsksForEveryFeed() {
        val vm = LibraryViewModel(env.deps)
        vm.refreshAll()
        assertEquals(listOf<Long?>(null), env.refreshes)
    }
}
