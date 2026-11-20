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
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile

/** Library › Downloads (owner, 2026-11-18): what is on the phone for listening offline, and what is on its way. */
@RunWith(AndroidJUnit4::class)
class DownloadedListTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private var feedId = 0L

    /** Oldest first: ids[0] is "Episode 1". */
    private lateinit var ids: List<Long>

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
        feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..5).map { episode(it) }))
        ids = repos.episodes.forFeed(feedId).first().map { it.id }.reversed()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun file(id: Long, state: DownloadState, completedAt: Long? = null) =
        repos.media.upsert(MediaFile(id, "media/$feedId/$id.mp3", state, 10, 10, null, completedAt))

    private suspend fun listed() = repos.episodes.downloaded().first().map { it.id }

    @Test
    fun nothingDownloadedListsNothing() = runBlocking {
        assertEquals(emptyList<Long>(), listed())
    }

    @Test
    fun downloadsInProgressComeFirstThenFinishedOnesNewestFirstAndFailedOnesNotAtAll() = runBlocking {
        file(ids[0], DownloadState.Complete, completedAt = 100)
        file(ids[1], DownloadState.Complete, completedAt = 300)
        file(ids[2], DownloadState.Failed)
        file(ids[3], DownloadState.Downloading)
        file(ids[4], DownloadState.Complete, completedAt = 200)
        assertEquals(listOf(ids[3], ids[1], ids[4], ids[0]), listed())
    }

    @Test
    fun aDeletedDownloadLeavesTheList() = runBlocking {
        file(ids[0], DownloadState.Complete, completedAt = 100)
        file(ids[1], DownloadState.Complete, completedAt = 200)
        repos.media.delete(ids[1])
        assertEquals(listOf(ids[0]), listed())
    }

    /** Codex review, PR #28: an id list stopped at 500, so rows past it lost their progress. */
    @Test
    fun everyDownloadedEpisodesRecordIsTherePastFiveHundred() = runBlocking {
        val many = (6..(PlaybackRepository.MAX_IDS + 10)).map { episode(it) }
        repos.feeds.applyRefresh(feedId, parsedFeed((1..5).map { episode(it) } + many))
        val all = repos.episodes.forFeed(feedId).first().map { it.id }
        all.forEach { id ->
            file(id, DownloadState.Complete, completedAt = id)
            repos.playback.savePosition(id, 1_000, 1_800_000)
        }
        val notDownloaded = (repos.feeds.subscribe("https://other.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(notDownloaded, parsedFeed(listOf(episode(999, url = "https://other.example.com/999.mp3"))))
        val other = repos.episodes.forFeed(notDownloaded).first().single().id
        repos.playback.savePosition(other, 1_000, 1_800_000)

        val records = repos.playback.recordsForDownloads().first()
        assertEquals(all.toSet(), records.keys)
        assertEquals(1_000L, records.getValue(all.last()).positionMs)
    }

    @Test
    fun theListIsCapped() = runBlocking {
        ids.forEachIndexed { i, id -> file(id, DownloadState.Complete, completedAt = i.toLong()) }
        assertEquals(listOf(ids[4], ids[3]), repos.episodes.downloaded(limit = 2).first().map { it.id })
    }
}
