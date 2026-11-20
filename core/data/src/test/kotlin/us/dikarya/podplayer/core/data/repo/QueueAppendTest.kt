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

/** A car's "add to queue" (PR #14 review): appended only if absent, checked and written in one transaction. */
@RunWith(AndroidJUnit4::class)
class QueueAppendTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private lateinit var ids: List<Long>

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, TestClock()) { "device-1" }
        val feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..3).map { episode(it) }))
        ids = repos.episodes.forFeed(feedId).first().map { it.id }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queueIds() = repos.queue.entries().first().map { it.episodeId }

    @Test
    fun appendAddsAtTheEndAndLeavesAQueuedEpisodeWhereItIs() = runBlocking {
        repos.queue.add(ids[0])
        repos.queue.add(ids[1])
        assertTrue(repos.queue.appendIfAbsent(ids[2]))
        assertFalse(repos.queue.appendIfAbsent(ids[0]))
        assertEquals(listOf(ids[0], ids[1], ids[2]), queueIds())
        assertFalse("unknown ids are ignored", repos.queue.appendIfAbsent(9_999))
    }
}
