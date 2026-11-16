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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.DAY
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile

/** SPEC §9 "Auto-download" and "Clean-up": what the jobs layer reads from the library. */
@RunWith(AndroidJUnit4::class)
class DownloadRulesRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private var feedId = 0L

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
        feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
    }

    @After
    fun tearDown() = db.close()

    /** Episodes published at [times], refreshed in one go; returns their ids in the same order. */
    private suspend fun publish(vararg times: Long, startAt: Int = 1): List<Long> {
        val existing = repos.episodes.forFeed(feedId).first().size
        val items = (1..existing).map { episode(it, publishedAt = repos.episodes.forFeed(feedId).first().first { e -> e.guid == "guid-$it" }.publishedAt) } +
            times.mapIndexed { i, t -> episode(startAt + i, publishedAt = t) }
        repos.feeds.applyRefresh(feedId, parsedFeed(items))
        val byGuid = repos.episodes.forFeed(feedId).first().associateBy { it.guid }
        return times.indices.map { byGuid.getValue("guid-${startAt + it}").id }
    }

    private suspend fun candidates() = repos.downloadRules.autoDownloadCandidates(50).map { it.id }

    @Test
    fun autoDownloadIsOffForAShowUntilTurnedOnAndStampsTheTime() = runBlocking {
        assertEquals(false, repos.feedSettings.autoDownload(feedId).first())
        assertNull(repos.feedSettings.autoDownloadSince(feedId))
        repos.feedSettings.setAutoDownload(feedId, true)
        assertEquals(true, repos.feedSettings.autoDownload(feedId).first())
        assertEquals(clock.now, repos.feedSettings.autoDownloadSince(feedId))
        clock.advance(DAY)
        repos.feedSettings.setAutoDownload(feedId, true)
        assertEquals("turning it on again keeps the first time", clock.now - DAY, repos.feedSettings.autoDownloadSince(feedId))
        repos.feedSettings.setAutoDownload(feedId, false)
        assertNull(repos.feedSettings.autoDownloadSince(feedId))
    }

    @Test
    fun theSpeedAndAutoDownloadDoNotOverwriteEachOther() = runBlocking {
        repos.feedSettings.setSpeed(feedId, 1.5f)
        repos.feedSettings.setAutoDownload(feedId, true)
        repos.feedSettings.setSpeed(feedId, 1.2f)
        assertEquals(true, repos.feedSettings.autoDownload(feedId).first())
        assertEquals(1.2f, repos.feedSettings.speed(feedId).first())
    }

    @Test
    fun subscribingWithTheDefaultOnTurnsItOnFromThatMoment() = runBlocking {
        val id = (repos.feeds.subscribe("https://new.example.com/rss", false, autoDownload = true) as SubscribeResult.Subscribed).feedId
        assertEquals(clock.now, repos.feedSettings.autoDownloadSince(id))
        val off = (repos.feeds.subscribe("https://off.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        assertNull(repos.feedSettings.autoDownloadSince(off))
    }

    @Test
    fun onlyEpisodesPublishedAfterItWasTurnedOnAreCandidates() = runBlocking {
        val old = publish(clock.now - 2 * DAY, clock.now - DAY)
        assertEquals("off: nothing", emptyList<Long>(), candidates())
        repos.feedSettings.setAutoDownload(feedId, true)
        assertEquals("the back catalogue is never flooded in", emptyList<Long>(), candidates())
        val new = publish(clock.now + 1_000, clock.now + 2_000, startAt = 3)
        assertEquals("oldest first", new, candidates())
        assertTrue(old.none { it in candidates() })
    }

    @Test
    fun aCandidateQueuedOnceDownloadedPlayedOrGoneFromTheFeedIsNotOneAgain() = runBlocking {
        repos.feedSettings.setAutoDownload(feedId, true)
        val (a, b, c, d) = publish(clock.now + 1, clock.now + 2, clock.now + 3, clock.now + 4)
        repos.downloadRules.markAutoQueued(a)
        repos.media.upsert(MediaFile(b, "media/$feedId/$b.mp3", DownloadState.Failed, 0, null, null, null))
        repos.playback.setPlayed(c, true)
        assertEquals(listOf(d), candidates())
        // d leaves the feed: no longer a candidate.
        repos.feeds.applyRefresh(feedId, parsedFeed((1..3).map { episode(it, publishedAt = clock.now + it) }))
        assertEquals(emptyList<Long>(), candidates())
        assertEquals(clock.now, db.episodes().get(a)!!.autoDownloadedAt)
    }

    @Test
    fun aRefreshNeverClearsTheAutoQueuedMark() = runBlocking {
        repos.feedSettings.setAutoDownload(feedId, true)
        val (a) = publish(clock.now + 1)
        repos.downloadRules.markAutoQueued(a)
        repos.feeds.applyRefresh(feedId, parsedFeed(listOf(episode(1, title = "Renamed", publishedAt = clock.now + 1))))
        assertEquals(clock.now, db.episodes().get(a)!!.autoDownloadedAt)
    }

    @Test
    fun starredEpisodesWithoutADownloadAreListedForKeepStarred() = runBlocking {
        val (a, b, c) = publish(clock.now - 3 * DAY, clock.now - 2 * DAY, clock.now - DAY)
        repos.starred.setStarred(a, true)
        repos.starred.setStarred(b, true)
        repos.starred.setStarred(c, true)
        repos.media.upsert(MediaFile(b, "media/$feedId/$b.mp3", DownloadState.Complete, 10, 10, null, 5))
        repos.downloadRules.markAutoQueued(c)
        assertEquals(listOf(a), repos.downloadRules.starredNotDownloaded(50).map { it.id })
    }

    @Test
    fun cleanUpCandidatesCarryEverythingTheRulesNeed() = runBlocking {
        val (a, b, c) = publish(clock.now - 3 * DAY, clock.now - 2 * DAY, clock.now - DAY)
        for (id in listOf(a, b, c)) repos.media.upsert(MediaFile(id, "media/$feedId/$id.mp3", DownloadState.Complete, 10, 10, null, 100 + id))
        repos.media.upsert(MediaFile(c, "media/$feedId/$c.mp3", DownloadState.Downloading, 5, 10, null, null))
        repos.playback.setPlayed(a, true)
        repos.starred.setStarred(b, true)
        repos.queue.add(b)
        val byId = repos.downloadRules.cleanUpCandidates().associateBy { it.episodeId }
        assertEquals("only finished downloads", setOf(a, b), byId.keys)
        assertEquals(CleanUpCandidate(a, completedAt = 100 + a, played = true, playedAt = clock.now, starred = false, queued = false), byId[a])
        assertEquals(CleanUpCandidate(b, completedAt = 100 + b, played = false, playedAt = null, starred = true, queued = true), byId[b])
        assertEquals(byId[a], repos.downloadRules.cleanUpCandidate(a))
        assertNull(repos.downloadRules.cleanUpCandidate(c))
    }

    @Test
    fun thePlayedCountChangesWhenAnEpisodeIsMarkedPlayed() = runBlocking {
        val (a) = publish(clock.now)
        assertEquals(0, repos.downloadRules.playedCount().first())
        repos.playback.setPlayed(a, true)
        assertEquals(1, repos.downloadRules.playedCount().first())
    }
}
