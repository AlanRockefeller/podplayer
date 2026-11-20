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
import org.junit.Assert.assertNotNull
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
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.net.UrlProblem

/**
 * Playing from a preview without subscribing (owner, 2026-11-18): the show is kept aside in the database so the
 * usual playback, queue and position rules apply, but it is not a subscription until the owner subscribes.
 */
@RunWith(AndroidJUnit4::class)
class KeptAsideShowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories

    @Before
    fun setUp() {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun keepAside(url: String = FEED_URL, count: Int = 3): Long =
        (repos.feeds.keepAside(url, parsedFeed((1..count).map { episode(it) })) as KeepAsideResult.Stored).feedId

    private suspend fun episodeIds(feedId: Long) = repos.episodes.forFeed(feedId).first().map { it.id }

    @Test
    fun keepingAsideStoresTheEpisodesButNoSubscription() = runBlocking {
        val id = keepAside()
        assertEquals(3, episodeIds(id).size)
        val feed = repos.feeds.get(id)!!
        assertFalse(feed.subscribed)
        assertNull("never owner-typed", feed.ownerApprovedHost)
        assertEquals("A Show", feed.title)
        assertEquals(emptyList<Long>(), repos.feeds.feeds().first().map { it.id })
        assertEquals(listOf(id), repos.feeds.allFeeds().first().map { it.id })
    }

    @Test
    fun aKeptAsideShowIsNeverRefreshedInTheBackgroundNorOnHome() = runBlocking {
        keepAside()
        assertEquals(emptyList<Long>(), repos.feeds.allFeedIds())
        assertEquals(emptyList<Long>(), repos.feeds.dueFeedIds(clock()))
        assertEquals(emptyList<Any>(), repos.episodes.latest(10).first())
    }

    @Test
    fun aKeptAsideShowIsNeverAutoDownloaded() = runBlocking {
        val id = keepAside()
        repos.feedSettings.setAutoDownload(id, true)
        clock.advance(1_000)
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) } + episode(99, publishedAt = clock() + 1)))
        assertEquals(emptyList<Any>(), repos.downloadRules.autoDownloadCandidates(10))
    }

    @Test
    fun subscribingPromotesItKeepingEpisodesAndPositions() = runBlocking {
        val id = keepAside()
        val first = episodeIds(id).first()
        repos.playback.savePosition(first, 61_000, 1_800_000)
        repos.queue.add(first)
        clock.advance(5_000)
        assertEquals(SubscribeResult.Subscribed(id), repos.feeds.subscribe(FEED_URL, ownerTyped = false, autoDownload = true))
        val feed = repos.feeds.get(id)!!
        assertTrue(feed.subscribed)
        assertEquals(clock(), feed.subscribedAt)
        assertEquals(listOf(id), repos.feeds.feeds().first().map { it.id })
        assertEquals(61_000L, repos.playback.get(first)!!.positionMs)
        assertEquals(listOf(first), repos.queue.entries().first().map { it.episodeId })
        assertEquals(3, repos.episodes.latest(10).first().size)
        assertEquals(clock(), repos.feedSettings.autoDownloadSince(id))
        assertEquals(listOf(id), repos.feeds.allFeedIds())
        // A second subscribe is the usual duplicate.
        assertEquals(SubscribeResult.AlreadySubscribed(id), repos.feeds.subscribe(FEED_URL, ownerTyped = false))
    }

    @Test
    fun typingTheAddressOfAKeptAsideShowApprovesItsHost() = runBlocking {
        val id = keepAside("http://home.lan/feed.xml")
        repos.feeds.subscribe("http://home.lan/feed.xml", ownerTyped = true)
        assertEquals("home.lan", repos.feeds.get(id)!!.ownerApprovedHost)
    }

    @Test
    fun keepingAsideAgainReusesTheRowAndKeepingAsideASubscriptionLeavesItSubscribed() = runBlocking {
        val id = keepAside(count = 2)
        assertEquals(id, keepAside(count = 3))
        assertEquals(3, episodeIds(id).size)
        val subscribed = (repos.feeds.subscribe("https://other.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        assertEquals(subscribed, keepAside("https://other.example.com/rss"))
        assertTrue(repos.feeds.get(subscribed)!!.subscribed)
    }

    @Test
    fun keepingAsideRefusesBadAddresses() = runBlocking {
        assertEquals(KeepAsideResult.Refused(UrlProblem.DisallowedScheme), repos.feeds.keepAside("file:///etc/passwd", parsedFeed(emptyList())))
        assertEquals(emptyList<Any>(), repos.feeds.allFeeds().first())
    }

    @Test
    fun cleanUpRemovesOnlyKeptAsideShowsNothingUses() = runBlocking {
        val unused = keepAside("https://a.example.com/rss")
        val queued = keepAside("https://b.example.com/rss")
        val starred = keepAside("https://c.example.com/rss")
        val started = keepAside("https://d.example.com/rss")
        val finished = keepAside("https://e.example.com/rss")
        val downloaded = keepAside("https://f.example.com/rss")
        val loaded = keepAside("https://g.example.com/rss")
        val subscribed = (repos.feeds.subscribe("https://h.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(subscribed, parsedFeed(listOf(episode(1))))
        repos.queue.add(episodeIds(queued).first())
        repos.starred.setStarred(episodeIds(starred).first(), true)
        repos.playback.savePosition(episodeIds(started).first(), 30_000, 1_800_000)
        repos.playback.setPlayed(episodeIds(finished).first(), true)
        val file = episodeIds(downloaded).first()
        repos.media.upsert(MediaFile(file, "media/$downloaded/$file.mp3", DownloadState.Failed, 0, null, null, null))

        assertEquals("nothing within a week", 0, repos.feeds.removeUnusedKeptAside(loadedEpisodeId = episodeIds(loaded).first()))
        clock.advance(PAST_A_WEEK)
        assertEquals(2, repos.feeds.removeUnusedKeptAside(loadedEpisodeId = episodeIds(loaded).first()))

        val left = repos.feeds.allFeeds().first().map { it.id }.toSet()
        assertEquals(setOf(queued, starred, started, downloaded, loaded, subscribed), left)
        assertNull(repos.feeds.get(unused))
        assertNull(repos.feeds.get(finished))
        assertNotNull(repos.feeds.get(subscribed))
    }

    @Test
    fun aShowOpenedOrPlayedThisWeekStays() = runBlocking {
        val opened = keepAside("https://a.example.com/rss")
        val played = keepAside("https://b.example.com/rss")
        clock.advance(PAST_A_WEEK)
        keepAside("https://a.example.com/rss")
        repos.playback.setPlayed(episodeIds(played).first(), true)
        assertEquals(0, repos.feeds.removeUnusedKeptAside(null))
        clock.advance(PAST_A_WEEK)
        assertEquals(2, repos.feeds.removeUnusedKeptAside(null))
        assertNull(repos.feeds.get(opened))
    }

    @Test
    fun theDatabasePathPlaybackUsesFindsAKeptAsideEpisode() = runBlocking {
        // RoomPlaybackStore loads by episode id and feed id, and the engine ejects an episode only when its row is gone.
        val id = keepAside()
        val episode = episodeIds(id).first()
        assertNotNull(repos.episodes.get(episode))
        assertEquals(id, repos.feeds.get(repos.episodes.get(episode)!!.feedId)!!.id)
        assertTrue(repos.playback.savePosition(episode, 12_000, 1_800_000))
        assertEquals(12_000L, repos.playback.get(episode)!!.positionMs)
        assertEquals(listOf(episode), repos.playback.inProgress(5).first().map { it.id })
    }

    @Test
    fun aSubscriptionMovingToAKeptAsideAddressTakesItsPlace() = runBlocking {
        val aside = keepAside("https://new.example.com/rss")
        val subscribed = (repos.feeds.subscribe("https://old.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        assertEquals(MoveResult.Moved, repos.feeds.moveUrl(subscribed, "https://new.example.com/rss"))
        assertNull(repos.feeds.get(aside))
        assertEquals("https://new.example.com/rss", repos.feeds.get(subscribed)!!.url)
    }

    @Test
    fun aKeptAsideShowWithADownloadDoesNotGiveWay() = runBlocking {
        val aside = keepAside("https://new.example.com/rss")
        val file = episodeIds(aside).first()
        repos.media.upsert(MediaFile(file, "media/$aside/$file.mp3", DownloadState.Complete, 10, 10, null, 1))
        val subscribed = (repos.feeds.subscribe("https://old.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        assertEquals(MoveResult.AlreadySubscribed(aside), repos.feeds.moveUrl(subscribed, "https://new.example.com/rss"))
        assertNotNull(repos.feeds.get(aside))
    }

    private companion object {
        const val PAST_A_WEEK = FeedRepository.KEPT_ASIDE_GRACE_MS + 1
    }

    @Test
    fun cleanUpNeverTouchesSubscriptions() = runBlocking {
        val id = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        clock.advance(PAST_A_WEEK)
        assertEquals(0, repos.feeds.removeUnusedKeptAside(null))
        assertNotNull(repos.feeds.get(id))
    }
}
