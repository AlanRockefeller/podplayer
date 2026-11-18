/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.home

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** SPEC §12 Home: Continue card, New episodes (play, queue, download), Your shows. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HomeViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    @Test
    fun anEmptyLibraryAsksTheOwnerToFindAShow() {
        val vm = HomeViewModel(env.deps)
        val state = awaitState(vm.state) { !it.loading }
        assertTrue(state.noShows)
        assertNull(state.continueItem)
        assertTrue(state.newEpisodes.isEmpty())
    }

    @Test
    fun scrollingToTheEndLoadsOlderEpisodes() = runBlocking {
        // Owner, 2026-11-16: Home stopped at about 20 episodes; it should keep going as you scroll.
        env.show("Alpha", episodes = 70, publishedBase = 1_790_000_000_000L)
        val vm = HomeViewModel(env.deps)
        val first = awaitState(vm.state) { it.newEpisodes.size == HomeViewModel.PAGE }
        assertTrue(first.canLoadMore)
        vm.loadMore()
        val second = awaitState(vm.state) { it.newEpisodes.size == 2 * HomeViewModel.PAGE }
        assertEquals("the first page is kept, in order", first.newEpisodes.map { it.id }, second.newEpisodes.take(HomeViewModel.PAGE).map { it.id })
        vm.loadMore()
        val all = awaitState(vm.state) { it.newEpisodes.size == 70 }
        assertFalse("nothing older is left", all.canLoadMore)
    }

    @Test
    fun newEpisodesAreNewestFirstAcrossShowsAndPlayedOnesStayInPlace() = runBlocking {
        // Owner, 2026-11-18: "Playing episode shouldn't remove it from feed, but should mark it played, but let it
        // stay where it is."
        val a = env.show("Alpha", episodes = 2, publishedBase = 1_790_000_000_000L)
        val b = env.show("Beta", episodes = 2, publishedBase = 1_790_000_000_000L + BrowseTestEnv.DAY / 2)
        val played = env.episodeIds(a).first()
        env.repos.playback.setPlayed(played, true)
        val vm = HomeViewModel(env.deps)
        val state = awaitState(vm.state) { it.newEpisodes.size == 4 && it.newEpisodes.any { item -> item.played } }
        assertEquals(listOf("Beta 1", "Alpha 1", "Beta 2", "Alpha 2"), state.newEpisodes.map { it.episode.title })
        assertEquals(listOf(false, true, false, false), state.newEpisodes.map { it.started })
        assertEquals("Beta", state.newEpisodes.first().showTitle)
        assertEquals(listOf("Alpha", "Beta"), state.shows.map { it.title })
        assertFalse(state.noShows)
        assertTrue(b > 0)
    }

    @Test
    fun theContinueCardIsTheLastStartedEpisodeUntilTheSessionHasOne() = runBlocking {
        val feed = env.show("Alpha", episodes = 3)
        val (newest, middle) = env.episodeIds(feed)
        env.repos.playback.savePosition(middle, 600_000, 1_800_000)
        val vm = HomeViewModel(env.deps)
        val started = awaitState(vm.state) { it.continueItem != null }
        assertEquals(middle, started.continueItem!!.id)
        assertEquals(1f / 3, started.continueItem!!.progress!!, 0.01f)
        assertFalse("not loaded, so its button plays it", started.continueItem!!.playing)
        // Owner, 2026-11-18: a started episode stays in its place in the list too, shown as started.
        assertEquals(listOf(newest, middle), started.newEpisodes.map { it.id }.take(2))
        assertTrue(started.newEpisodes.single { it.id == middle }.started)
        assertFalse(started.newEpisodes.single { it.id == newest }.started)

        // The session loads another episode: the card follows the session, the single source of truth.
        env.playback.playEpisode(newest)
        val playing = awaitState(vm.state) { it.continueItem?.id == newest }
        assertTrue(playing.continueItem!!.playing)
    }

    @Test
    fun rowCommandsGoToTheSessionAndTheLibrary() = runBlocking {
        val feed = env.show("Alpha", episodes = 2)
        val (first, second) = env.episodeIds(feed)
        val vm = HomeViewModel(env.deps)
        val state = awaitState(vm.state) { it.newEpisodes.size == 2 }
        val item = state.newEpisodes.first { it.id == first }

        vm.actions.togglePlay(item)
        assertEquals(listOf("play $first"), env.playback.commands)
        val loaded = awaitState(vm.state) { it.continueItem?.id == first }
        vm.actions.togglePlay(loaded.continueItem!!)
        assertEquals("a second tap pauses", "pause", env.playback.commands.last())

        vm.actions.toggleQueue(state.newEpisodes.first { it.id == second })
        awaitState(vm.state) { s -> s.newEpisodes.firstOrNull { it.id == second }?.queued == true }
        assertEquals(listOf(second), env.repos.queue.entries().first().map { it.episodeId })

        vm.actions.download(second)
        eventually { env.downloads.calls == listOf("download $second") }

        vm.refresh()
        assertEquals(listOf<Long?>(null), env.refreshes)
    }

    @Test
    fun goingOfflineIsShown() {
        env.show("Alpha")
        val vm = HomeViewModel(env.deps)
        awaitState(vm.state) { it.online && !it.loading }
        env.online.value = false
        awaitState(vm.state) { !it.online }
    }
}
