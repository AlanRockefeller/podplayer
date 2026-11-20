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
import us.dikarya.podplayer.core.data.DAY
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.db.MediaFileEntity
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.feed.Soundbite
import us.dikarya.podplayer.core.feed.Transcript
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.net.UrlProblem

@RunWith(AndroidJUnit4::class)
class FeedRepositoryTest {
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

    private suspend fun subscribe(url: String = FEED_URL, ownerTyped: Boolean = false): Long =
        (repos.feeds.subscribe(url, ownerTyped) as SubscribeResult.Subscribed).feedId

    @Test
    fun subscribeStoresCanonicalUrlAndOwnerApprovedHostOnlyWhenTyped() = runBlocking {
        val typed = subscribe("HTTP://Home.Lan:8080/feed.xml", ownerTyped = true)
        val found = subscribe("https://feeds.example.com/other.xml", ownerTyped = false)
        val feeds = repos.feeds.feeds().first().associateBy { it.id }
        assertEquals("http://home.lan:8080/feed.xml", feeds.getValue(typed).url)
        assertEquals("home.lan", feeds.getValue(typed).ownerApprovedHost)
        assertNull(feeds.getValue(found).ownerApprovedHost)
        assertFalse(feeds.getValue(typed).isSecure)
        assertTrue(feeds.getValue(found).isSecure)
    }

    @Test
    fun subscribeRefusesBadUrlsAndReportsDuplicates() = runBlocking {
        assertEquals(SubscribeResult.Refused(UrlProblem.DisallowedScheme), repos.feeds.subscribe("file:///etc/passwd", true))
        assertEquals(SubscribeResult.Refused(UrlProblem.DisallowedScheme), repos.feeds.subscribe("javascript:alert(1)", true))
        assertEquals(SubscribeResult.Refused(UrlProblem.Malformed), repos.feeds.subscribe("not a url", true))
        val id = subscribe()
        assertEquals(SubscribeResult.AlreadySubscribed(id), repos.feeds.subscribe(FEED_URL, false))
        assertEquals(1, repos.feeds.feeds().first().size)
    }

    @Test
    fun firstRefreshInsertsEveryEpisodeAndTheChannel() = runBlocking {
        val id = subscribe()
        val result = repos.feeds.applyRefresh(id, parsedFeed((1..5).map { episode(it) })) as RefreshApplied.Applied
        assertEquals(5, result.inserted)
        val feed = repos.feeds.feed(id).first()!!
        assertEquals("A Show", feed.title)
        assertEquals(listOf("Technology", "News"), feed.categories)
        val episodes = repos.episodes.forFeed(id).first()
        assertEquals(5, episodes.size)
        // Newest first.
        assertEquals("Episode 5", episodes.first().title)
        assertEquals("https://cdn.example.com/audio/5.mp3?token=SECRET5", episodes.first().enclosure.url)
        assertTrue(episodes.all { it.inFeed })
    }

    @Test
    fun secondIdenticalRefreshWritesNothing() = runBlocking {
        val id = subscribe()
        val feed = parsedFeed((1..5).map { episode(it) })
        repos.feeds.applyRefresh(id, feed)
        val second = repos.feeds.applyRefresh(id, feed) as RefreshApplied.Applied
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertEquals(5, second.unchanged)
    }

    @Test
    fun repostUpdatesMetadataAndKeepsPlayedStatePositionQueueAndDownload() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) }))
        val target = repos.episodes.forFeed(id).first().single { it.guid == "guid-2" }
        repos.playback.savePosition(target.id, 600_000L, 1_800_000L)
        repos.playback.setPlayed(target.id, true)
        repos.queue.add(target.id)
        db.mediaFiles().upsert(
            MediaFileEntity(target.id, "media/$id/${target.id}.mp3", DownloadState.Complete, 1_000L, 1_000L, updatedAt = 1L),
        )

        val reposted = episode(2, title = "Episode 2 (re-post)", publishedAt = 1_790_000_000_000L + 30 * DAY)
        val result = repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1), reposted, episode(3)))) as RefreshApplied.Applied
        assertEquals(1, result.updated)
        assertEquals(1, result.reposted)

        val after = repos.episodes.get(target.id)!!
        assertEquals("Episode 2 (re-post)", after.title)
        assertEquals(1_790_000_000_000L + 30 * DAY, after.publishedAt)
        assertTrue(repos.playback.get(target.id)!!.played)
        assertEquals(listOf(target.id), repos.queue.entries().first().map { it.episodeId })
        assertEquals(DownloadState.Complete, db.mediaFiles().get(target.id)!!.state)
    }

    @Test
    fun aRefreshWithoutADateKeepsTheStoredDate() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1))))
        val before = repos.episodes.forFeed(id).first().single()
        repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1, title = "Renamed", publishedAt = null))))
        val after = repos.episodes.get(before.id)!!
        assertEquals("Renamed", after.title)
        assertEquals(before.publishedAt, after.publishedAt)
    }

    @Test
    fun undatedNewItemsUseTheFirstSeenTime() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1, publishedAt = null))))
        assertEquals(clock.now, repos.episodes.forFeed(id).first().single().publishedAt)
    }

    @Test
    fun removedItemsStayMarkedAndComeBack() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) }))
        val removed = repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1), episode(3)))) as RefreshApplied.Applied
        assertEquals(1, removed.noLongerInFeed)
        val gone = repos.episodes.forFeed(id).first().single { it.guid == "guid-2" }
        assertFalse(gone.inFeed)
        assertEquals(3, repos.episodes.forFeed(id).first().size)

        val back = repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) })) as RefreshApplied.Applied
        assertEquals(1, back.backInFeed)
        assertTrue(repos.episodes.get(gone.id)!!.inFeed)
    }

    @Test
    fun hostMoveWithNewGuidsKeepsEpisodesAndState() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) }))
        val before = repos.episodes.forFeed(id).first().associateBy { it.title }
        repos.playback.savePosition(before.getValue("Episode 2").id, 123_000L, null)

        // Same titles and days, new GUIDs and a new CDN.
        val moved = (1..3).map { episode(it, guid = "new-host-$it", url = "https://new-cdn.example.net/$it.mp3") }
        val result = repos.feeds.applyRefresh(id, parsedFeed(moved)) as RefreshApplied.Applied
        assertEquals(0, result.inserted)
        assertEquals(3, result.updated)

        val after = repos.episodes.forFeed(id).first()
        assertEquals(3, after.size)
        val two = after.single { it.title == "Episode 2" }
        assertEquals(before.getValue("Episode 2").id, two.id)
        assertEquals("new-host-2", two.guid)
        assertEquals("https://new-cdn.example.net/2.mp3", two.enclosure.url)
        assertEquals(123_000L, repos.playback.get(two.id)!!.positionMs)
    }

    @Test
    fun duplicateGuidsAreKeptApart() = runBlocking {
        val id = subscribe()
        val items = (1..4).map { episode(it, guid = "same-guid") }
        val result = repos.feeds.applyRefresh(id, parsedFeed(items)) as RefreshApplied.Applied
        assertEquals(4, result.inserted)
        val again = repos.feeds.applyRefresh(id, parsedFeed(items)) as RefreshApplied.Applied
        assertEquals(0, again.inserted)
        assertEquals(4, again.unchanged)
    }

    @Test
    fun extrasAreStoredForLaterMilestones() = runBlocking {
        val id = subscribe()
        val item = episode(1).copy(
            transcripts = listOf(Transcript("https://t.example.com/1.vtt", "text/vtt", "en", null)),
            soundbites = listOf(Soundbite(1_000, 30_000, "Best bit")),
        )
        repos.feeds.applyRefresh(id, parsedFeed(listOf(item)))
        val row = db.episodes().get(repos.episodes.forFeed(id).first().single().id)!!
        val extras = EpisodeExtras.decode(row.extrasJson)
        assertEquals("https://t.example.com/1.vtt", extras.transcripts.single().url)
        assertEquals(30_000L, extras.soundbites.single().durationMs)
        assertNull(EpisodeExtras.encode(EpisodeExtras()))
        // Damaged JSON in a row is not a crash.
        assertEquals(EpisodeExtras(), EpisodeExtras.decode("{not json"))
    }

    @Test
    fun aFailureInsideTheTransactionLeavesNothingApplied() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed((1..3).map { episode(it) }))
        repos.feeds.faultAfterInserts = { throw IllegalStateException("simulated crash") }
        val next = (2..6).map { episode(it, title = "Changed $it") }
        val outcome = runCatching { repos.feeds.applyRefresh(id, parsedFeed(next, title = "New title")) }
        assertTrue(outcome.isFailure)
        repos.feeds.faultAfterInserts = null

        val episodes = repos.episodes.forFeed(id).first()
        assertEquals(3, episodes.size)
        assertTrue(episodes.all { it.inFeed && it.title!!.startsWith("Episode") })
        assertEquals("A Show", repos.feeds.feed(id).first()!!.title)
    }

    @Test
    fun refreshOfAnUnsubscribedFeedIsReported() = runBlocking {
        assertEquals(RefreshApplied.FeedGone, repos.feeds.applyRefresh(999L, parsedFeed(listOf(episode(1)))))
    }

    @Test
    fun unsubscribeRemovesEpisodesAndTheirState() = runBlocking {
        val id = subscribe()
        repos.feeds.applyRefresh(id, parsedFeed((1..2).map { episode(it) }))
        val ep = repos.episodes.forFeed(id).first().first()
        repos.queue.add(ep.id)
        repos.playback.savePosition(ep.id, 1_000L, null)
        repos.feeds.unsubscribe(id)
        assertTrue(repos.feeds.feeds().first().isEmpty())
        assertNull(repos.episodes.get(ep.id))
        assertTrue(repos.queue.entries().first().isEmpty())
        assertNull(repos.playback.get(ep.id))
    }

    @Test
    fun tenThousandItemsApplyInOneTransaction() = runBlocking {
        val id = subscribe()
        val items = (1..10_000).map { episode(it, notes = null) }
        val started = System.nanoTime()
        val result = repos.feeds.applyRefresh(id, parsedFeed(items)) as RefreshApplied.Applied
        assertEquals(10_000, result.inserted)
        // Everything gone from the feed at once: one IN list per 500 ids.
        val emptied = repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1, notes = null)))) as RefreshApplied.Applied
        assertEquals(9_999, emptied.noLongerInFeed)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 60)
        assertEquals(10_000, db.episodes().countForFeed(id))
    }

    @Test
    fun toStringNeverShowsTokens() = runBlocking {
        val id = subscribe()
        // CodeRabbit review (PR 3): a GUID can be a URL with a token in it.
        repos.feeds.applyRefresh(id, parsedFeed(listOf(episode(1, guid = "https://example.invalid/item?token=SECRETGUID"))))
        val text = repos.feeds.feeds().first().toString() + repos.episodes.forFeed(id).first().toString() +
            db.feeds().get(id).toString() + db.episodes().get(repos.episodes.forFeed(id).first().single().id).toString()
        assertFalse(text, "SECRET" in text)
        assertNotNull(text)
    }
}
