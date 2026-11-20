/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult

/** Applying an import file's stars, played marks and positions to a subscribed show (M1-S9c uses this). */
@RunWith(AndroidJUnit4::class)
class ImportApplierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private var feedId = 0L
    private lateinit var byTitle: Map<String, Long>

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
        feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..4).map { episode(it) }))
        byTitle = repos.episodes.forFeed(feedId).first().associate { it.title!! to it.id }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun starsPlayedMarksAndPositionsAreApplied() = runBlocking {
        val report = ImportApplier.apply(
            repos,
            feedId,
            listOf(
                ImportEpisode(title = "Episode 1", starred = true, starredAt = 1_000L),
                ImportEpisode(title = "Episode 2", played = true),
                ImportEpisode(title = "Episode 3", positionMs = 600_000),
                ImportEpisode(title = "Not in this feed", starred = true),
            ),
        )
        assertEquals(3, report.matched)
        assertEquals(listOf("Not in this feed"), report.unmatched)
        assertEquals(listOf(byTitle.getValue("Episode 1")), repos.starred.starred().first().map { it.id })
        assertEquals("the file's own star time keeps its order", 1_000L, repos.episodes.get(byTitle.getValue("Episode 1"))!!.starredAt)
        assertTrue(repos.playback.get(byTitle.getValue("Episode 2"))!!.played)
        assertEquals(600_000L, repos.playback.get(byTitle.getValue("Episode 3"))!!.positionMs)
    }

    @Test
    fun theLibrarysOwnProgressIsNeverOverwritten() = runBlocking {
        val id = byTitle.getValue("Episode 3")
        repos.playback.savePosition(id, 120_000, null)
        ImportApplier.apply(repos, feedId, listOf(ImportEpisode(title = "Episode 3", positionMs = 600_000)))
        assertEquals(120_000L, repos.playback.get(id)!!.positionMs)

        val played = byTitle.getValue("Episode 4")
        repos.playback.setPlayed(played, true)
        ImportApplier.apply(repos, feedId, listOf(ImportEpisode(title = "Episode 4", positionMs = 600_000)))
        assertTrue(repos.playback.get(played)!!.played)
        assertEquals(0L, repos.playback.get(played)!!.positionMs)
    }

    @Test
    fun applyingTwiceChangesNothingMore() = runBlocking {
        val entries = listOf(ImportEpisode(title = "Episode 1", starred = true), ImportEpisode(title = "Episode 2", played = true))
        ImportApplier.apply(repos, feedId, entries)
        val actions = repos.actions.unsynced(100).size
        ImportApplier.apply(repos, feedId, entries)
        assertEquals(actions, repos.actions.unsynced(100).size)
        assertFalse(repos.episodes.get(byTitle.getValue("Episode 2"))!!.starred)
    }
}
