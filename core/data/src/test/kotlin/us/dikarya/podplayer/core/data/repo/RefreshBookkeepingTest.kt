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
import us.dikarya.podplayer.core.data.media.MediaPaths
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.net.Validators

/** What refresh (SPEC §8) and downloads (SPEC §9) store about feeds and files (M1-S5). */
@RunWith(AndroidJUnit4::class)
class RefreshBookkeepingTest {
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
        feedId = (repos.feeds.subscribe(FEED_URL, ownerTyped = true) as SubscribeResult.Subscribed).feedId
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aNewFeedIsDueAndHasNoValidators() = runBlocking {
        val target = repos.feeds.refreshTarget(feedId)!!
        assertEquals(FEED_URL, target.url)
        assertEquals("feeds.example.com", target.ownerApprovedHost)
        assertTrue(target.validators.isEmpty)
        assertEquals(0, target.consecutiveFailures)
        assertEquals(listOf(feedId), repos.feeds.dueFeedIds(clock.now))
        assertEquals(listOf(feedId), repos.feeds.allFeedIds())
        assertFalse("no token in toString", "SECRETTOKEN" in target.toString())
    }

    @Test
    fun successStoresValidatorsAndResetsTheBackoff() = runBlocking {
        repos.feeds.recordRefreshFailure(feedId, "boom", clock.now) { clock.now + it * 1000L }
        repos.feeds.recordRefreshSuccess(feedId, Validators.of("\"v1\"", "Sat, 07 Nov 2026 10:00:00 GMT"), clock.now)
        val target = repos.feeds.refreshTarget(feedId)!!
        assertEquals("\"v1\"", target.validators.etag)
        assertEquals("Sat, 07 Nov 2026 10:00:00 GMT", target.validators.lastModified)
        assertEquals(0, target.consecutiveFailures)
        assertNull(target.nextRefreshAt)
        val feed = repos.feeds.get(feedId)!!
        assertEquals(clock.now, feed.lastRefreshSuccessAt)
        assertNull(feed.nextRefreshAt)
    }

    @Test
    fun notModifiedKeepsTheValidators() = runBlocking {
        repos.feeds.recordRefreshSuccess(feedId, Validators.of("\"v1\"", null), clock.now)
        clock.advance(1000)
        repos.feeds.recordRefreshSuccess(feedId, null, clock.now)
        assertEquals("\"v1\"", repos.feeds.refreshTarget(feedId)!!.validators.etag)
        assertEquals(clock.now, repos.feeds.get(feedId)!!.lastRefreshSuccessAt)
    }

    @Test
    fun failuresCountUpAndScheduleTheNextAttempt() = runBlocking {
        val seen = mutableListOf<Int>()
        repeat(3) {
            repos.feeds.recordRefreshFailure(feedId, "Server error at $FEED_URL", clock.now) { failures ->
                seen += failures
                clock.now + failures * 60_000L
            }
        }
        assertEquals(listOf(1, 2, 3), seen)
        val target = repos.feeds.refreshTarget(feedId)!!
        assertEquals(3, target.consecutiveFailures)
        assertEquals(clock.now + 180_000L, target.nextRefreshAt)
        val feed = repos.feeds.get(feedId)!!
        assertEquals(clock.now, feed.lastRefreshErrorAt)
        assertFalse("the stored error is redacted", "SECRETTOKEN" in feed.lastRefreshError!!)
        assertTrue(repos.feeds.dueFeedIds(clock.now).isEmpty())
        assertEquals(listOf(feedId), repos.feeds.dueFeedIds(clock.now + 180_000L))
    }

    @Test
    fun aMoveUpdatesTheUrlUnlessAnotherSubscriptionHasIt() = runBlocking {
        val moved = repos.feeds.moveUrl(feedId, "https://new.example.com/show.xml?token=SECRETTOKEN")
        assertEquals(MoveResult.Moved, moved)
        assertEquals("https://new.example.com/show.xml?token=SECRETTOKEN", repos.feeds.get(feedId)!!.url)
        assertEquals("the approval stays with the host the owner typed", "feeds.example.com", repos.feeds.get(feedId)!!.ownerApprovedHost)

        val other = (repos.feeds.subscribe("https://other.example.com/feed.xml", false) as SubscribeResult.Subscribed).feedId
        assertEquals(MoveResult.AlreadySubscribed(other), repos.feeds.moveUrl(feedId, "https://other.example.com/feed.xml"))
        assertEquals(MoveResult.Unchanged, repos.feeds.moveUrl(feedId, "https://new.example.com/show.xml?token=SECRETTOKEN"))
        assertEquals(MoveResult.Refused, repos.feeds.moveUrl(feedId, "ftp://new.example.com/show.xml"))
        assertEquals(MoveResult.FeedGone, repos.feeds.moveUrl(9999, "https://x.example.com/"))
    }

    @Test
    fun mediaValidatorsCompletionAndUsedBytes() = runBlocking {
        repos.feeds.applyRefresh(feedId, parsedFeed((1..3).map { episode(it) }))
        val ids = repos.episodes.forFeed(feedId).first().map { it.id }
        val paths = MediaPaths { tmp.root }
        ids.forEachIndexed { i, id ->
            repos.media.upsert(MediaFile(id, paths.relativePathFor(feedId, id, "mp3"), DownloadState.Downloading, 1000L * (i + 1), null, null, null))
        }
        assertEquals(2000L + 3000L, repos.media.usedBytes(exceptEpisodeId = ids[0]))

        repos.media.saveValidators(ids[0], Validators.of("\"etag\"", null))
        assertEquals("\"etag\"", repos.media.validators(ids[0]).etag)
        // A progress update keeps the validators.
        repos.media.upsert(repos.media.get(ids[0])!!.copy(bytesDownloaded = 1500))
        assertEquals("\"etag\"", repos.media.validators(ids[0]).etag)

        repos.media.markComplete(ids[0], bytes = 1500, durationMs = 60_000, mimeType = "audio/mpeg", at = clock.now)
        val done = repos.media.get(ids[0])!!
        assertEquals(DownloadState.Complete, done.state)
        assertEquals(60_000L, done.probedDurationMs)
        assertEquals(clock.now, done.completedAt)
        assertEquals("audio/mpeg", repos.media.mimeType(ids[0]))

        assertEquals(3, repos.media.forFeed(feedId).size)
        assertEquals(3, repos.media.observeAll().first().size)
    }
}
