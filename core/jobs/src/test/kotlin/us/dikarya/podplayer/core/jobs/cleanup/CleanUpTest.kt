/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.cleanup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.repo.CleanUpCandidate
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.jobs.HOUR
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.xmlResponse
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.MediaFile

private const val DAY = 24 * HOUR

/** SPEC §9 "Clean-up of downloaded files": each rule, each exemption, the log, and no race with a download. */
@RunWith(AndroidJUnit4::class)
class CleanUpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var env: TestEnv
    private var loaded: Long? = null
    private var feedId = 0L
    private lateinit var ids: List<Long>

    @Before
    fun setUp() = runBlocking {
        env = TestEnv(File(tmp.root, "env"))
        feedId = env.subscribe()
        env.routes["/feed.xml"] = {
            xmlResponse(feedXml((1..5).map { Item("g$it", "Episode $it", "https://cdn.example.test/$it.mp3") }))
        }
        env.refresher.refresh(RefreshRequest.Feed(feedId))
        ids = (1..5).map { n -> env.repos().episodes.forFeed(feedId).first().single { it.guid == "g$n" }.id }
    }

    @After
    fun tearDown() = env.close()

    private fun cleanUp(beforeEach: suspend (Long) -> Unit = {}) = CleanUp(
        repositories = { env.data.awaitRepositories() },
        runner = env.runner,
        settings = { env.data.settings.cleanUp.first() },
        loadedEpisodeId = { loaded },
        log = env.log,
        clock = env.clock,
        beforeEach = beforeEach,
    )

    /** A finished download of [episodeId], finished now. Returns its file. */
    private suspend fun downloaded(episodeId: Long): File {
        val path = env.data.paths.relativePathFor(feedId, episodeId, "mp3")
        val file = env.data.paths.resolve(path)!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64))
        }
        env.repos().media.upsert(MediaFile(episodeId, path, DownloadState.Complete, 64, 64, 1_000, env.clock.now), env.clock.now)
        return file
    }

    private suspend fun gone(episodeId: Long, file: File): Boolean = !file.exists() && env.repos().media.get(episodeId) == null

    @Test
    fun playedEpisodesGoThreeDaysAfterTheyWereFinished() = runBlocking {
        val file = downloaded(ids[0])
        env.repos().playback.setPlayed(ids[0], true)
        env.clock.advance(3 * DAY - 1)
        assertEquals(0, cleanUp().run())
        assertTrue(file.isFile)
        env.clock.advance(1)
        assertEquals(1, cleanUp().run())
        assertTrue(gone(ids[0], file))
        val row = env.repos().logs.downloadLog(ids[0], 5).first().first()
        assertEquals(DownloadEvent.Deleted, row.event)
        assertTrue(row.message!!, row.message!!.contains("played"))
        assertTrue(EpisodeActionKind.Deleted in env.repos().actions.unsynced(50).map { it.kind })
    }

    @Test
    fun unplayedDownloadsGoFourteenDaysAfterTheyFinished() = runBlocking {
        val file = downloaded(ids[0])
        env.clock.advance(14 * DAY - 1)
        assertEquals(0, cleanUp().run())
        env.clock.advance(1)
        assertEquals(1, cleanUp().run())
        assertTrue(gone(ids[0], file))
        val row = env.repos().logs.downloadLog(ids[0], 5).first().first()
        assertEquals(DownloadEvent.Deleted, row.event)
        assertTrue(row.message!!, row.message!!.contains("not played"))
    }

    @Test
    fun aPlayedEpisodeCountsFromWhenItWasFinishedNotDownloaded() = runBlocking {
        val file = downloaded(ids[0])
        env.clock.advance(13 * DAY)
        env.repos().playback.setPlayed(ids[0], true)
        env.clock.advance(2 * DAY)
        assertEquals("played 2 days ago: kept, though downloaded 15 days ago", 0, cleanUp().run())
        assertTrue(file.isFile)
    }

    @Test
    fun theNumbersAreConfigurableAndZeroMeansNever() = runBlocking {
        val played = downloaded(ids[0])
        env.repos().playback.setPlayed(ids[0], true)
        val unplayed = downloaded(ids[1])
        env.data.settings.setCleanUp(CleanUpSettings(playedDays = 7, unplayedDays = CleanUpSettings.NEVER))
        env.clock.advance(6 * DAY)
        assertEquals(0, cleanUp().run())
        env.clock.advance(DAY)
        assertEquals(1, cleanUp().run())
        assertFalse(played.exists())
        env.clock.advance(365 * DAY)
        assertEquals(0, cleanUp().run())
        assertTrue("never", unplayed.isFile)
    }

    @Test
    fun starredQueuedAndLoadedEpisodesAreNeverDeleted() = runBlocking {
        val starred = downloaded(ids[0])
        val queued = downloaded(ids[1])
        val loadedFile = downloaded(ids[2])
        val plain = downloaded(ids[3])
        for (id in ids.take(4)) env.repos().playback.setPlayed(id, true)
        env.repos().starred.setStarred(ids[0], true)
        env.repos().queue.add(ids[1])
        loaded = ids[2]
        env.clock.advance(400 * DAY)
        assertEquals(1, cleanUp().run())
        assertTrue(starred.isFile && queued.isFile && loadedFile.isFile)
        assertFalse(plain.exists())
    }

    @Test
    fun theRulesInOnePlace() {
        val now = 100 * DAY
        val settings = CleanUpSettings()
        fun c(played: Boolean = false, playedAt: Long? = null, completedAt: Long? = 0, starred: Boolean = false, queued: Boolean = false) =
            CleanUpCandidate(1, completedAt, played, playedAt, starred, queued)
        assertEquals(CleanUpReason.Played(3), CleanUpRules.reason(c(played = true, playedAt = now - 3 * DAY), settings, null, now))
        assertNull(CleanUpRules.reason(c(played = true, playedAt = now - 3 * DAY + 1), settings, null, now))
        assertEquals(CleanUpReason.Unplayed(14), CleanUpRules.reason(c(completedAt = now - 14 * DAY), settings, null, now))
        assertNull("no finish time: never guessed", CleanUpRules.reason(c(completedAt = null), settings, null, now))
        assertNull(CleanUpRules.reason(c(played = true, playedAt = 0, starred = true), settings, null, now))
        assertNull(CleanUpRules.reason(c(played = true, playedAt = 0, queued = true), settings, null, now))
        assertNull(CleanUpRules.reason(c(played = true, playedAt = 0), settings, loadedEpisodeId = 1, now = now))
        assertNull("a clock that went back deletes nothing", CleanUpRules.reason(c(completedAt = now + DAY), settings, null, now))
    }

    @Test
    fun cleanUpWaitsForARunningDownloadOfTheSameEpisodeAndLeavesItAlone() = runBlocking {
        val file = downloaded(ids[0])
        env.clock.advance(15 * DAY)
        val downloadHasLock = CompletableDeferred<Unit>()
        val cleanUpChoseIt = CompletableDeferred<Unit>()
        // A download of the same episode (the owner deleted and downloaded it again) holds the per-episode lock.
        val download = launch(Dispatchers.IO) {
            env.locks.withLock(ids[0]) {
                downloadHasLock.complete(Unit)
                cleanUpChoseIt.await()
                val record = env.repos().media.get(ids[0])!!
                env.repos().media.upsert(record.copy(state = DownloadState.Downloading, bytesDownloaded = 10, completedAt = null))
                file.writeBytes(ByteArray(10))
            }
        }
        downloadHasLock.await()
        val deleted = async(Dispatchers.IO) { cleanUp { if (it == ids[0]) cleanUpChoseIt.complete(Unit) }.run() }
        withTimeout(30_000) {
            download.join()
            assertEquals(0, deleted.await())
        }
        assertTrue("the running download's file is untouched", file.isFile)
        assertEquals(DownloadState.Downloading, env.repos().media.get(ids[0])!!.state)
        assertNotNull(env.repos().media.get(ids[0]))
    }
}
