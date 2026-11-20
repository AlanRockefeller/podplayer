/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.model.EpisodeActionKind

/** SPEC §7 and §12 "Starred episodes": star, unstar, Undo, the Starred list and its search, the sync actions. */
@RunWith(AndroidJUnit4::class)
class StarredRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private var feedId = 0L
    private var otherFeedId = 0L

    /** Oldest first: ids[0] is "Episode 1". */
    private lateinit var ids: List<Long>
    private lateinit var otherIds: List<Long>

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
        feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..4).map { episode(it) }, title = "Science Hour"))
        ids = repos.episodes.forFeed(feedId).first().map { it.id }.reversed()
        otherFeedId = (repos.feeds.subscribe("https://other.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(
            otherFeedId,
            parsedFeed(listOf(episode(1, guid = "o1", title = "100% real_talk", url = "https://cdn.example.com/o1.mp3")), title = "Gardening"),
        )
        otherIds = repos.episodes.forFeed(otherFeedId).first().map { it.id }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun starredIds(query: String = "") = repos.starred.starred(query).first().map { it.id }

    @Test
    fun starringSetsTheFlagAndTimeAndAppendsAnAction() = runBlocking {
        val change = repos.starred.setStarred(ids[0], true)!!
        val episode = repos.episodes.get(ids[0])!!
        assertTrue(episode.starred)
        assertEquals(clock.now, episode.starredAt)
        assertNull("it wasn't starred before", change.previousStarredAt)
        assertTrue(change.starred)
        assertEquals(listOf(EpisodeActionKind.Starred), repos.actions.unsynced(10).map { it.kind })
    }

    @Test
    fun starringTwiceChangesNothing() = runBlocking {
        repos.starred.setStarred(ids[0], true)
        val first = repos.episodes.get(ids[0])!!.starredAt
        clock.advance(1_000)
        assertNull(repos.starred.setStarred(ids[0], true))
        assertEquals(first, repos.episodes.get(ids[0])!!.starredAt)
        assertEquals(1, repos.actions.unsynced(10).size)
        assertNull("unstarring an unstarred episode is a no-op too", repos.starred.setStarred(ids[1], false))
    }

    @Test
    fun unstarThenUndoPutsTheOldTimeBackSoTheOrderIsKept() = runBlocking {
        repos.starred.setStarred(ids[0], true)
        clock.advance(1_000)
        repos.starred.setStarred(ids[1], true)
        clock.advance(1_000)
        repos.starred.setStarred(ids[2], true)
        assertEquals("newest starred first", listOf(ids[2], ids[1], ids[0]), starredIds())

        clock.advance(1_000)
        val change = repos.starred.setStarred(ids[1], false)!!
        assertFalse(repos.episodes.get(ids[1])!!.starred)
        assertNull(repos.episodes.get(ids[1])!!.starredAt)
        assertEquals(listOf(ids[2], ids[0]), starredIds())

        repos.starred.undo(change)
        assertEquals(listOf(ids[2], ids[1], ids[0]), starredIds())
        assertEquals(
            listOf(EpisodeActionKind.Starred, EpisodeActionKind.Starred, EpisodeActionKind.Starred, EpisodeActionKind.Unstarred, EpisodeActionKind.Starred),
            repos.actions.unsynced(10).map { it.kind },
        )
    }

    @Test
    fun undoingAStarUnstars() = runBlocking {
        val change = repos.starred.setStarred(ids[3], true)!!
        repos.starred.undo(change)
        assertFalse(repos.episodes.get(ids[3])!!.starred)
        assertEquals(emptyList<Long>(), starredIds())
    }

    @Test
    fun starringAPlayedEpisodeKeepsItPlayed() = runBlocking {
        repos.playback.setPlayed(ids[0], true)
        repos.starred.setStarred(ids[0], true)
        assertTrue(repos.playback.get(ids[0])!!.played)
        assertEquals(0L, repos.playback.get(ids[0])!!.positionMs)
    }

    @Test
    fun aRefreshKeepsTheStar() = runBlocking {
        repos.starred.setStarred(ids[0], true)
        repos.feeds.applyRefresh(feedId, parsedFeed((1..4).map { episode(it, notes = "<p>changed $it</p>") }, title = "Science Hour"))
        assertTrue(repos.episodes.get(ids[0])!!.starred)
    }

    @Test
    fun searchMatchesEpisodeOrShowTitlesAndTreatsWildcardsAsText() = runBlocking {
        repos.starred.setStarred(ids[0], true)
        clock.advance(1)
        repos.starred.setStarred(ids[2], true)
        clock.advance(1)
        repos.starred.setStarred(otherIds[0], true)

        assertEquals(listOf(ids[2], ids[0]), starredIds("science"))
        assertEquals(listOf(ids[2]), starredIds("episode 3"))
        assertEquals(listOf(otherIds[0]), starredIds("garden"))
        assertEquals("% is a character, not a wildcard", listOf(otherIds[0]), starredIds("100%"))
        assertEquals("_ is a character, not a wildcard", listOf(otherIds[0]), starredIds("real_talk"))
        assertEquals(emptyList<Long>(), starredIds("real-talk"))
        assertEquals("blank searches list everything", 3, starredIds("   ").size)
    }

    @Test
    fun aShowsStarredEpisodesAndUnknownIds() = runBlocking {
        repos.starred.setStarred(ids[1], true)
        repos.starred.setStarred(otherIds[0], true)
        assertEquals(setOf(ids[1]), repos.starred.idsForFeed(feedId).first())
        assertNull("an unknown episode is ignored", repos.starred.setStarred(999_999, true))
    }

    @Test
    fun unsubscribingDropsTheShowsStars() = runBlocking {
        repos.starred.setStarred(otherIds[0], true)
        repos.feeds.unsubscribe(otherFeedId)
        assertEquals(emptyList<Long>(), starredIds())
    }
}
