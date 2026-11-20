/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.queue

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState

/** SPEC §12 Queue: the database queue (CLAUDE.md: the source of truth), reordered, played next, removed. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class QueueViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    /** Four queued episodes a, b, c, d (in that order) and the view model, once it shows them. */
    private fun queued(): Pair<List<Long>, QueueViewModel> = runBlocking {
        val ids = env.episodeIds(env.show("Alpha", episodes = 4))
        ids.forEach { env.repos.queue.add(it) }
        val vm = QueueViewModel(env.deps)
        awaitState(vm.state) { it.items.size == 4 }
        ids to vm
    }

    private fun order(vm: QueueViewModel, expected: List<Long>) {
        awaitState(vm.state) { s -> s.items.map { it.id } == expected }
        assertEquals(expected, runBlocking { env.repos.queue.entries().first().map { it.episodeId } })
    }

    @Test
    fun theQueueIsListedInOrderWithTheLoadedEpisodeMarked() {
        val (ids, vm) = queued()
        assertEquals(ids, vm.state.value.items.map { it.id })
        env.playback.playEpisode(ids[0])
        val state = awaitState(vm.state) { s -> s.items.first().loaded }
        assertTrue(state.items.drop(1).none { it.loaded })
    }

    @Test
    fun anEmptyQueueSaysSo() {
        val vm = QueueViewModel(env.deps)
        val state = awaitState(vm.state) { !it.loading }
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun moveUpAndMoveDownChangeTheDatabaseOrder() {
        val (ids, vm) = queued()
        val (a, b, c, d) = ids
        vm.moveDown(a)
        order(vm, listOf(b, a, c, d))
        vm.moveUp(d)
        order(vm, listOf(b, a, d, c))
        // At either end nothing moves. Writes run in order, so once the next move shows, both have been applied
        // (CodeRabbit review, PR 2: waiting for an order that was already shown proved nothing).
        vm.moveUp(b)
        vm.moveDown(c)
        vm.moveDown(a)
        order(vm, listOf(b, d, a, c))
    }

    @Test
    fun aDragIsCommittedAsOneMove() {
        val (ids, vm) = queued()
        val (a, b, c, d) = ids
        vm.move(a, toIndex = 2)
        order(vm, listOf(b, c, a, d))
        vm.move(d, toIndex = 0)
        order(vm, listOf(d, b, c, a))
    }

    @Test
    fun playNextGoesRightAfterThePlayingEpisode() {
        val (ids, vm) = queued()
        val (a, b, c, d) = ids
        // Nothing loaded: next means first.
        vm.playNext(c)
        order(vm, listOf(c, a, b, d))
        // The engine keeps the playing episode at the top of the queue.
        env.playback.playEpisode(c)
        awaitState(vm.state) { s -> s.items.first().loaded }
        vm.playNext(d)
        order(vm, listOf(c, d, a, b))
    }

    @Test
    fun removeTakesItOutOfTheQueueOnly() = runBlocking {
        val (ids, vm) = queued()
        val (a, b, c, d) = ids
        vm.remove(b)
        order(vm, listOf(a, c, d))
        assertTrue("the episode itself stays", env.repos.episodes.get(b) != null)
    }

    @Test
    fun playingARowIsACommand() {
        val (ids, vm) = queued()
        vm.actions.togglePlay(vm.state.value.items[2])
        assertEquals("play ${ids[2]}", env.playback.commands.last())
    }
}
