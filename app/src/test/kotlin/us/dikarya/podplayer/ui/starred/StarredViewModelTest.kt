/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.starred

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.repo.StarChange
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.feed.FeedFormat
import us.dikarya.podplayer.core.feed.ParsedEnclosure
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.ParsedFeed
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.EpisodeItem
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually
import us.dikarya.podplayer.ui.home.HomeViewModel
import us.dikarya.podplayer.ui.show.ShowViewModel

/** SPEC §12 "Starred episodes": star and Undo from any screen, Library › Starred, Home's row, the show filter. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StarredViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun starred(id: Long) = runBlocking { env.repos.episodes.get(id)!!.starred }

    @Test
    fun starringFromARowStarsAtOnceAndUndoUnstars() {
        val feed = env.show("Alpha", episodes = 2)
        val (newest, _) = env.episodeIds(feed)
        val vm = ShowViewModel(env.deps, feed)
        val changes = mutableListOf<StarChange>()
        val collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.actions.starChanges.collect { changes += it } }
        try {
            val item = awaitState(vm.state) { it.episodes.size == 2 }.episodes.first { it.id == newest }
            assertFalse(item.starred)
            vm.actions.toggleStar(item)
            // No confirmation: it is starred, and the change is offered for Undo.
            awaitState(vm.state) { s -> s.episodes.first { it.id == newest }.starred }
            eventually { changes.size == 1 }
            assertTrue(changes.single().starred)

            vm.actions.undoStar(changes.single())
            awaitState(vm.state) { s -> !s.episodes.first { it.id == newest }.starred }
            assertFalse(starred(newest))
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun theStarredListIsNewestStarredFirstAndSearchable() = runBlocking {
        val alpha = env.show("Alpha", episodes = 2)
        val garden = env.show("Garden Talk", episodes = 1)
        val (a1, a2) = env.episodeIds(alpha)
        val g1 = env.episodeIds(garden).single()
        env.repos.starred.setStarred(a2, true)
        Thread.sleep(5)
        env.repos.starred.setStarred(g1, true)
        Thread.sleep(5)
        env.repos.starred.setStarred(a1, true)

        val vm = StarredViewModel(env.deps)
        val all = awaitState(vm.state) { it.items.size == 3 }
        assertEquals(listOf(a1, g1, a2), all.items.map { it.id })
        assertEquals("Garden Talk", all.items[1].showTitle)

        vm.onQueryChange("garden")
        assertEquals(listOf(g1), awaitState(vm.state) { it.items.size == 1 }.items.map { it.id })
        vm.onQueryChange("alpha 2")
        assertEquals(listOf(a2), awaitState(vm.state) { s -> s.items.map { it.id } == listOf(a2) }.items.map { it.id })
        vm.onQueryChange("nothing like it")
        assertTrue(awaitState(vm.state) { it.items.isEmpty() && !it.loading }.noMatches)
    }

    @Test
    fun shareTextListsWhatIsShownWithoutEnclosureUrls() = runBlocking {
        val alpha = env.show("Alpha", episodes = 1)
        env.repos.starred.setStarred(env.episodeIds(alpha).single(), true)
        // Item links from the feed: one is the enclosure itself (left out), one a page (kept). CodeRabbit review
        // (PR 2): without links, keeping every link and dropping every link both passed.
        val linked = (env.repos.feeds.subscribe("https://linked.example.com/feed.xml", ownerTyped = false) as SubscribeResult.Subscribed).feedId
        val enclosure = "https://cdn.example.com/linked/1.mp3?token=SECRET"
        env.repos.feeds.applyRefresh(
            linked,
            ParsedFeed(
                FeedFormat.Rss, title = "Linked", author = null, description = null,
                episodes = listOf(
                    ParsedEpisode(guid = "l1", title = "Enclosure link", publishedAt = 2L, enclosure = ParsedEnclosure(enclosure, "audio/mpeg", 1), link = enclosure),
                    ParsedEpisode(
                        guid = "l2", title = "Page link", publishedAt = 1L,
                        enclosure = ParsedEnclosure("https://cdn.example.com/linked/2.mp3", "audio/mpeg", 1), link = "https://linked.example.com/episodes/2",
                    ),
                ),
            ),
        )
        env.episodeIds(linked).forEach { env.repos.starred.setStarred(it, true) }
        val vm = StarredViewModel(env.deps)
        awaitState(vm.state) { it.items.size == 3 }
        val text = vm.shareText(heading = "Starred episodes", untitled = "Untitled")
        assertTrue(text, "Alpha 1 — Alpha" in text)
        assertTrue(text, "Enclosure link — Linked" in text)
        assertFalse(text, "cdn.example.com" in text)
        assertTrue("a page link is kept: $text", "https://linked.example.com/episodes/2" in text)
    }

    @Test
    fun homeShowsAStarredRowOnlyWhenSomethingIsStarred() = runBlocking {
        val alpha = env.show("Alpha", episodes = 2)
        val (a1, _) = env.episodeIds(alpha)
        val vm = HomeViewModel(env.deps)
        assertEquals(emptyList<EpisodeItem>(), awaitState(vm.state) { !it.loading }.starred)
        env.repos.starred.setStarred(a1, true)
        assertEquals(listOf(a1), awaitState(vm.state) { it.starred.isNotEmpty() }.starred.map { it.id })
    }

    @Test
    fun aShowPageCanFilterToItsStarredEpisodes() = runBlocking {
        val alpha = env.show("Alpha", episodes = 3)
        val (_, middle, _) = env.episodeIds(alpha)
        env.repos.starred.setStarred(middle, true)
        val vm = ShowViewModel(env.deps, alpha)
        awaitState(vm.state) { it.episodes.size == 3 }
        vm.toggleStarredOnly()
        val filtered = awaitState(vm.state) { it.starredOnly && it.episodes.size == 1 }
        assertEquals(listOf(middle), filtered.episodes.map { it.id })
        vm.toggleStarredOnly()
        awaitState(vm.state) { !it.starredOnly && it.episodes.size == 3 }
        assertEquals(1, env.repos.starred.starred().first().size)
    }
}
