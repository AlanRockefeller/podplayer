/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
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
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.model.PositionCause
import us.dikarya.podplayer.core.model.PositionChange

/** Queue, playback records, per-show settings, episode actions, media files and the two DB logs. */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private var feedId = 0L
    private lateinit var ids: List<Long>

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
        feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..5).map { episode(it) }))
        // Oldest first: ids[0] is "Episode 1".
        ids = repos.episodes.forFeed(feedId).first().map { it.id }.reversed()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queueIds() = repos.queue.entries().first().map { it.episodeId }

    @Test
    fun queueAddsAtTheEndOrAtAPositionAndStaysDense() = runBlocking {
        repos.queue.add(ids[0])
        repos.queue.add(ids[1])
        repos.queue.add(ids[2], index = 0)
        repos.queue.add(ids[3], index = 99)
        assertEquals(listOf(ids[2], ids[0], ids[1], ids[3]), queueIds())
        assertEquals(listOf(0, 1, 2, 3), repos.queue.entries().first().map { it.position })
    }

    @Test
    fun addingAQueuedEpisodeMovesItInsteadOfDuplicating() = runBlocking {
        ids.take(3).forEach { repos.queue.add(it) }
        repos.queue.add(ids[2], index = 0)
        assertEquals(listOf(ids[2], ids[0], ids[1]), queueIds())
    }

    @Test
    fun moveAndRemoveRenumber() = runBlocking {
        ids.forEach { repos.queue.add(it) }
        repos.queue.move(ids[0], 4)
        assertEquals(listOf(ids[1], ids[2], ids[3], ids[4], ids[0]), queueIds())
        repos.queue.move(ids[4], 0)
        assertEquals(listOf(ids[4], ids[1], ids[2], ids[3], ids[0]), queueIds())
        repos.queue.remove(ids[2])
        assertEquals(listOf(0, 1, 2, 3), repos.queue.entries().first().map { it.position })
        // Unknown episodes and out-of-range targets are ignored or clamped, never a crash.
        repos.queue.move(123_456L, 0)
        repos.queue.move(ids[1], -5)
        assertEquals(ids[1], queueIds().first())
        repos.queue.clear()
        assertTrue(queueIds().isEmpty())
    }

    @Test
    fun shiftMovesOneStepFromWhereTheEpisodeIsNowAndStopsAtTheEnds() = runBlocking {
        val (a, b, c) = ids
        listOf(a, b, c).forEach { repos.queue.add(it) }
        repos.queue.shift(a, 1)
        assertEquals(listOf(b, a, c), queueIds())
        // Two quick taps: each starts from the database order, not from what a screen last saw.
        repos.queue.shift(a, 1)
        repos.queue.shift(a, 1)
        assertEquals(listOf(b, c, a), queueIds())
        repos.queue.shift(b, -1)
        assertEquals(listOf(b, c, a), queueIds())
        repos.queue.shift(123_456L, 1)
        assertEquals(listOf(b, c, a), queueIds())
    }

    @Test
    fun playNextGoesAfterThePlayingHeadOrToTheTop() = runBlocking {
        val (a, b, c, d) = ids
        listOf(a, b, c).forEach { repos.queue.add(it) }
        repos.queue.playNext(c, playingId = null)
        assertEquals(listOf(c, a, b), queueIds())
        repos.queue.playNext(b, playingId = c)
        assertEquals(listOf(c, b, a), queueIds())
        // Playing something that isn't the head (not the engine's order): next is the top.
        repos.queue.playNext(a, playingId = d)
        assertEquals(listOf(a, c, b), queueIds())
        // An episode not yet queued is added; the playing one itself stays where it is.
        repos.queue.playNext(d, playingId = a)
        repos.queue.playNext(a, playingId = a)
        assertEquals(listOf(a, d, c, b), queueIds())
    }

    @Test
    fun queueEpisodesFlowFollowsChanges() = runBlocking {
        repos.queue.episodes().test {
            assertEquals(emptyList<Long>(), awaitItem().map { it.id })
            repos.queue.add(ids[1])
            assertEquals(listOf(ids[1]), awaitItem().map { it.id })
            repos.queue.add(ids[0], index = 0)
            assertEquals(listOf(ids[0], ids[1]), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun positionsPersistAndPlayedResetsPosition() = runBlocking {
        repos.playback.savePosition(ids[0], 61_000L, 1_800_000L)
        clock.advance(1_000)
        repos.playback.savePosition(ids[0], 71_000L, null)
        val record = repos.playback.get(ids[0])!!
        assertEquals(71_000L, record.positionMs)
        // A save without a duration keeps the known one.
        assertEquals(1_800_000L, record.durationMs)
        assertEquals(clock.now, record.updatedAt)
        repos.playback.setPlayed(ids[0], true)
        assertTrue(repos.playback.get(ids[0])!!.played)
        assertEquals(0L, repos.playback.get(ids[0])!!.positionMs)
        repos.playback.setPlayed(ids[0], false)
        assertFalse(repos.playback.get(ids[0])!!.played)
        // Negative positions from a confused player are stored as 0.
        repos.playback.savePosition(ids[1], -5L, null)
        assertEquals(0L, repos.playback.get(ids[1])!!.positionMs)
    }

    @Test
    fun inProgressListsStartedUnfinishedEpisodesNewestFirst() = runBlocking {
        repos.playback.savePosition(ids[0], 10_000L, null)
        clock.advance(1_000)
        repos.playback.savePosition(ids[1], 20_000L, null)
        clock.advance(1_000)
        repos.playback.savePosition(ids[2], 30_000L, null)
        repos.playback.setPlayed(ids[2], true)
        assertEquals(listOf(ids[1], ids[0]), repos.playback.inProgress(10).first().map { it.id })
    }

    @Test
    fun perShowSpeedIsStoredAndBounded() = runBlocking {
        assertNull(repos.feedSettings.speed(feedId).first())
        repos.feedSettings.setSpeed(feedId, 1.5f)
        assertEquals(1.5f, repos.feedSettings.speed(feedId).first())
        repos.feedSettings.setSpeed(feedId, 50f)
        assertEquals(FeedSettingsRepository.MAX_SPEED, repos.feedSettings.speed(feedId).first())
        repos.feedSettings.setSpeed(feedId, Float.NaN)
        assertNull(repos.feedSettings.speed(feedId).first())
        repos.feedSettings.setSpeed(feedId, null)
        assertNull(repos.feedSettings.speed(feedId).first())
    }

    @Test
    fun episodeActionsAreAppendOnlyAndSurviveEpisodeDeletion() = runBlocking {
        repos.actions.append(ids[0], EpisodeActionKind.Play, 0L, 1_800_000L)
        repos.actions.append(ids[0], EpisodeActionKind.Pause, 60_000L, 1_800_000L)
        val unsynced = repos.actions.unsynced(10)
        assertEquals(listOf(EpisodeActionKind.Play, EpisodeActionKind.Pause), unsynced.map { it.kind })
        assertTrue(unsynced.all { it.deviceId == "device-1" && it.at == clock.now })
        repos.actions.markSynced(listOf(unsynced.first().id))
        assertEquals(1, repos.actions.unsynced(10).size)

        repos.feeds.unsubscribe(feedId)
        val kept = db.episodeActions().all()
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.episodeId == null })
    }

    @Test
    fun mediaFilesStoreRelativePathsOnly() = runBlocking {
        val file = MediaFile(ids[0], "media/$feedId/${ids[0]}.mp3", DownloadState.Complete, 10L, 10L, 1_000L, clock.now)
        repos.media.upsert(file)
        assertEquals(file, repos.media.get(ids[0]))
        val absolute = runCatching { repos.media.upsert(file.copy(relativePath = "/data/data/x/files/media/1.mp3")) }
        assertTrue(absolute.isFailure)
        val escaping = runCatching { repos.media.upsert(file.copy(relativePath = "media/../databases/podplayer.db")) }
        assertTrue(escaping.isFailure)
    }

    @Test
    fun refreshAndDownloadLogsAreRedactedCappedAndTrimmed() = runBlocking {
        repos.logs.addRefresh(
            feedId,
            RefreshOutcome.Failed,
            "Couldn't reach https://user:pw@feeds.example.com/show.xml?token=SECRET\nnext line",
        )
        val entry = repos.logs.refreshLog(feedId, 10).first().single()
        assertFalse(entry.message!!, "SECRET" in entry.message!!)
        assertFalse("pw@" in entry.message!!)
        assertTrue("https://feeds.example.com/show.xml" in entry.message!!)

        repos.logs.addDownload(ids[0], DownloadEvent.Failed, "x".repeat(50_000))
        assertEquals(LogRepository.MAX_MESSAGE, repos.logs.downloadLog(10).first().single().message!!.length)

        repeat(LogRepository.KEEP_ROWS + 20) { repos.logs.addDownload(ids[1], DownloadEvent.Started, "n=$it") }
        assertEquals(LogRepository.KEEP_ROWS, repos.logs.downloadLog(LogRepository.KEEP_ROWS * 2).first().size)
    }

    @Test
    fun positionLogKeepsEveryChangeWithItsCauseNewestFirst() = runBlocking {
        repos.logs.addPosition(PositionChange(episodeId = ids[0], at = 0, fromMs = 0, toMs = 61_000, cause = PositionCause.Restore, streaming = false))
        clock.advance(1_000)
        repos.logs.addPosition(PositionChange(episodeId = ids[0], at = 0, fromMs = 70_000, toMs = 55_000, cause = PositionCause.SkipBack, streaming = true))
        val log = repos.logs.positionLog(10).first()
        assertEquals(listOf(PositionCause.SkipBack, PositionCause.Restore), log.map { it.cause })
        // The clock stamps the row; the caller's `at` is ignored.
        assertEquals(clock.now, log.first().at)
        assertEquals(70_000L to 55_000L, log.first().fromMs to log.first().toMs)
        assertTrue(log.first().streaming)
    }

    @Test
    fun positionLogIsCappedAndOutlivesAnUnsubscribe() = runBlocking {
        repeat(LogRepository.KEEP_POSITION_ROWS + 30) {
            repos.logs.addPosition(PositionChange(episodeId = ids[1], at = 0, fromMs = it.toLong(), toMs = it + 1L, cause = PositionCause.Seek, streaming = false))
        }
        assertEquals(LogRepository.KEEP_POSITION_ROWS, repos.logs.positionLog(LogRepository.KEEP_POSITION_ROWS * 2).first().size)
        // The owner reviews this log after a jump; unsubscribing the show must not erase the evidence.
        repos.feeds.unsubscribe(feedId)
        assertEquals(LogRepository.KEEP_POSITION_ROWS, repos.logs.positionLog(LogRepository.KEEP_POSITION_ROWS * 2).first().size)
    }

    // --- What the browsing screens show (M1-S8) ---

    @Test
    fun recordsForAFeedAndForAListOfEpisodesFollowChanges() = runBlocking {
        val other = (repos.feeds.subscribe("https://other.example.com/feed.xml", false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(other, parsedFeed(listOf(episode(1, guid = "o1", url = "https://other.example.com/1.mp3"))))
        val otherEpisode = repos.episodes.forFeed(other).first().single().id
        repos.playback.savePosition(otherEpisode, 9_000, 60_000)

        repos.playback.recordsForFeed(feedId).test {
            assertEquals(emptyMap<Long, Any>(), awaitItem())
            repos.playback.savePosition(ids[0], 5_000, 60_000)
            assertEquals(5_000L, awaitItem()[ids[0]]?.positionMs)
            repos.playback.setPlayed(ids[1], true)
            val both = awaitItem()
            assertEquals(setOf(ids[0], ids[1]), both.keys)
            assertTrue(both.getValue(ids[1]).played)
            cancelAndIgnoreRemainingEvents()
        }
        val some = repos.playback.records(listOf(ids[0], otherEpisode, 999_999)).first()
        assertEquals(setOf(ids[0], otherEpisode), some.keys)
        assertEquals(emptyMap<Long, Any>(), repos.playback.records(emptyList()).first())
    }

    @Test
    fun eachFeedsNewestEpisodeDateIsKnownForSorting() = runBlocking {
        val newest = repos.episodes.forFeed(feedId).first().maxOf { it.publishedAt }
        val empty = (repos.feeds.subscribe("https://empty.example.com/feed.xml", false) as SubscribeResult.Subscribed).feedId
        val dates = repos.feeds.newestEpisodeDates().first()
        assertEquals(newest, dates[feedId])
        assertNull("a feed with no episodes has no date", dates[empty])
    }
}
