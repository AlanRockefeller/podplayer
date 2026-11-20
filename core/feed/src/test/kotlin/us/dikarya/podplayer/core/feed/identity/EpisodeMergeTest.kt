/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.identity

import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.feed.ParsedEnclosure
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.millis
import us.dikarya.podplayer.core.feed.parseFixture
import us.dikarya.podplayer.core.feed.feed

/**
 * SPEC §4 "Episode identity"; LESSONS "Episode identity": publishers reuse GUIDs, change them when moving
 * hosts, or re-post with a new date.
 */
class EpisodeMergeTest {
    private val day = 24 * 3600 * 1000L
    private val now = millis("2026-11-08T12:00:00Z")

    private fun ep(
        n: Int,
        guid: String? = "g$n",
        url: String? = "https://media.example.com/$n.mp3",
        title: String? = "Episode $n",
        date: Long? = millis("2026-10-01T10:00:00Z") + n * day,
    ) = ParsedEpisode(
        guid = guid,
        title = title,
        publishedAt = date,
        enclosure = url?.let { ParsedEnclosure(it, "audio/mpeg", null) },
    )

    /** Stores the result of a merge the way S4's repository will, giving inserts ids 1, 2, 3, ... */
    private class Store {
        private var nextId = 1L
        val rows = linkedMapOf<Long, StoredEpisode>()

        fun apply(changes: FeedChanges): FeedChanges {
            for (insert in changes.inserts) {
                val id = nextId++
                rows[id] = insert.toStored(id)
            }
            for (update in changes.updates) {
                val old = rows.getValue(update.id)
                rows[update.id] = old.copy(
                    guid = update.episode.guid,
                    enclosureUrl = update.episode.enclosure?.url,
                    title = update.episode.title,
                    publishedAt = update.episode.publishedAt ?: old.publishedAt,
                    fingerprint = update.fingerprint,
                    inFeed = true,
                )
            }
            for (id in changes.noLongerInFeed) rows[id] = rows.getValue(id).copy(inFeed = false)
            for (id in changes.backInFeed) rows[id] = rows.getValue(id).copy(inFeed = true)
            return changes
        }

        fun merge(items: List<ParsedEpisode>, at: Long): FeedChanges =
            apply(EpisodeMerge.merge(rows.values.toList(), items, at))

        fun idOf(guid: String) = rows.values.single { it.guid == guid }.id

        private fun NewEpisode.toStored(id: Long) = StoredEpisode(
            id = id,
            guid = episode.guid,
            enclosureUrl = episode.enclosure?.url,
            title = episode.title,
            publishedAt = publishedAt,
            fingerprint = fingerprint,
            inFeed = true,
        )
    }

    @Test
    fun `the first refresh inserts everything`() {
        val changes = EpisodeMerge.merge(emptyList(), listOf(ep(1), ep(2), ep(3)), now)
        assertEquals(3, changes.inserts.size)
        assertTrue(changes.updates.isEmpty() && changes.noLongerInFeed.isEmpty())
    }

    @Test
    fun `an unchanged feed changes nothing`() {
        val store = Store()
        store.merge(listOf(ep(1), ep(2), ep(3)), now)
        val second = store.merge(listOf(ep(1), ep(2), ep(3)), now + day)
        assertEquals(FeedChanges(unchanged = 3), second)
    }

    @Test
    fun `a metadata change updates the same episode`() {
        val store = Store()
        store.merge(listOf(ep(1), ep(2)), now)
        val id = store.idOf("g2")
        val changes = store.merge(listOf(ep(1), ep(2, title = "Episode 2 (fixed typo)")), now + day)
        assertEquals(listOf(id), changes.updates.map { it.id })
        assertEquals(MatchedBy.Guid, changes.updates.single().matchedBy)
        assertFalse(changes.updates.single().reposted)
        assertTrue(changes.inserts.isEmpty())
    }

    @Test
    fun `a re-post with the same GUID and a newer date is an update, never a new episode`() {
        val store = Store()
        store.merge(listOf(ep(1)), now)
        val changes = store.merge(listOf(ep(1, date = now + 3 * day)), now + 3 * day)
        assertTrue(changes.inserts.isEmpty())
        val update = changes.updates.single()
        assertEquals(store.idOf("g1"), update.id)
        assertTrue(update.reposted)
    }

    @Test
    fun `duplicate GUIDs are kept apart by enclosure URL`() {
        val store = Store()
        val items = listOf(
            ep(3, guid = "same"), ep(2, guid = "same"), ep(1, guid = "same"),
            ep(1, guid = "same"), // exact duplicate listing
        )
        val first = store.merge(items, now)
        assertEquals(3, first.inserts.size)
        assertEquals(1, first.duplicatesDropped)
        val second = store.merge(items, now + day)
        assertEquals(3, second.unchanged)
        assertTrue(second.inserts.isEmpty() && second.updates.isEmpty())
    }

    @Test
    fun `duplicate GUIDs on one shared enclosure fall back to title and day`() {
        val store = Store()
        val url = "https://media.example.com/placeholder.mp3"
        val items = listOf(ep(1, guid = "same", url = url), ep(2, guid = "same", url = url))
        assertEquals(2, store.merge(items, now).inserts.size)
        assertEquals(2, store.merge(items, now + day).unchanged)
    }

    @Test
    fun `the duplicate-GUID fixture keeps its episodes apart`() {
        val parsed = parseFixture("corpus/feeds/duplicate-guids.xml").feed().episodes
        val changes = EpisodeMerge.merge(emptyList(), parsed, now)
        assertEquals(4, changes.inserts.size)
        assertEquals(1, changes.duplicatesDropped)
    }

    @Test
    fun `items without a GUID are matched by enclosure URL`() {
        val store = Store()
        store.merge(listOf(ep(1, guid = null), ep(2, guid = null)), now)
        val changes = store.merge(listOf(ep(1, guid = null), ep(2, guid = null)), now + day)
        assertEquals(2, changes.unchanged)
    }

    @Test
    fun `items without a GUID whose enclosure changed are matched by title and day`() {
        // Dynamic ad insertion services often change the enclosure query on every fetch.
        val store = Store()
        store.merge(listOf(ep(1, guid = null, url = "https://media.example.com/1.mp3?v=1")), now)
        val id = store.rows.keys.single()
        val changes = store.merge(listOf(ep(1, guid = null, url = "https://media.example.com/1.mp3?v=2")), now + day)
        assertEquals(id, changes.updates.single().id)
        assertEquals(MatchedBy.TitleAndDay, changes.updates.single().matchedBy)
    }

    @Test
    fun `a host move that keeps GUIDs keeps every episode`() {
        val store = Store()
        store.merge((1..5).map { ep(it) }, now)
        val ids = store.rows.keys.toList()
        val moved = (1..5).map { ep(it, url = "https://newhost.example.org/audio/$it.mp3") }
        val changes = store.merge(moved, now + day)
        assertTrue(changes.inserts.isEmpty())
        assertEquals(ids, changes.updates.map { it.id }.sorted())
        assertTrue(changes.noLongerInFeed.isEmpty())
    }

    @Test
    fun `a host move that changes GUIDs and URLs keeps every episode by title and day`() {
        val store = Store()
        store.merge((1..5).map { ep(it) }, now)
        val ids = store.rows.keys.toList()
        // The new host also shifts publication times by a few hours.
        val moved = (1..5).map {
            ep(it, guid = "newhost-$it", url = "https://newhost.example.org/$it.mp3", date = millis("2026-10-01T16:00:00Z") + it * day)
        }
        val changes = store.merge(moved, now + day)
        assertTrue(changes.inserts.isEmpty())
        assertEquals(ids, changes.updates.map { it.id }.sorted())
        assertTrue(changes.updates.all { it.matchedBy == MatchedBy.TitleAndDay })
        assertTrue(changes.noLongerInFeed.isEmpty())
    }

    @Test
    fun `a new GUID does not take over an episode whose own GUID is still in the feed`() {
        val store = Store()
        store.merge(listOf(ep(1, title = "Trailer")), now)
        val sameTitleSameDay = ep(1, guid = "g-other", url = "https://media.example.com/other.mp3", title = "Trailer")
        val changes = store.merge(listOf(ep(1, title = "Trailer"), sameTitleSameDay), now + day)
        assertEquals(1, changes.inserts.size)
        assertEquals("g-other", changes.inserts.single().episode.guid)
        assertEquals(1, changes.unchanged)
    }

    @Test
    fun `items that disappear are marked, and marked back when they return`() {
        val store = Store()
        store.merge(listOf(ep(1), ep(2), ep(3)), now)
        val gone = store.merge(listOf(ep(1), ep(3)), now + day)
        assertEquals(listOf(store.idOf("g2")), gone.noLongerInFeed)
        assertTrue(gone.inserts.isEmpty())
        // Still gone: not reported again.
        assertTrue(store.merge(listOf(ep(1), ep(3)), now + 2 * day).noLongerInFeed.isEmpty())
        val back = store.merge(listOf(ep(1), ep(2), ep(3)), now + 3 * day)
        assertEquals(listOf(store.idOf("g2")), back.backInFeed)
        assertTrue(back.inserts.isEmpty())
    }

    @Test
    fun `an empty feed marks everything as no longer in the feed but deletes nothing`() {
        val store = Store()
        store.merge(listOf(ep(1), ep(2)), now)
        val changes = store.merge(emptyList(), now + day)
        assertEquals(2, changes.noLongerInFeed.size)
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `items without an enclosure are not episodes`() {
        val changes = EpisodeMerge.merge(emptyList(), listOf(ep(1), ep(2, url = null)), now)
        assertEquals(1, changes.inserts.size)
        assertEquals(1, changes.skippedWithoutEnclosure)
    }

    @Test
    fun `an undated item takes the first-seen time and keeps it`() {
        val store = Store()
        val first = store.merge(listOf(ep(1, date = null)), now)
        assertEquals(now, first.inserts.single().publishedAt)
        assertEquals(1, store.merge(listOf(ep(1, date = null)), now + 5 * day).unchanged)
        assertEquals(now, store.rows.values.single().publishedAt)
    }

    @Test
    fun `an undated item without GUID or stable URL is matched by a unique title`() {
        val store = Store()
        store.merge(listOf(ep(1, guid = null, url = "https://m.example.com/a?1", date = null)), now)
        val changes = store.merge(listOf(ep(1, guid = null, url = "https://m.example.com/a?2", date = null)), now + 9 * day)
        assertEquals(MatchedBy.Title, changes.updates.single().matchedBy)
        assertTrue(changes.inserts.isEmpty())
    }

    @Test
    fun `titles are compared after normalising case, width and spacing`() {
        val store = Store()
        store.merge(listOf(ep(1, guid = null, url = "https://m.example.com/a?1", title = "Ｅｐｉｓｏｄｅ  ONE")), now)
        val changes = store.merge(listOf(ep(1, guid = null, url = "https://m.example.com/a?2", title = "episode one")), now)
        assertEquals(1, changes.updates.size)
        assertTrue(changes.inserts.isEmpty())
    }

    @Test
    fun `the fingerprint is stable and sees every kept field`() {
        val a = ep(1)
        assertEquals(EpisodeMerge.fingerprint(a), EpisodeMerge.fingerprint(ep(1)))
        assertNotEquals(EpisodeMerge.fingerprint(a), EpisodeMerge.fingerprint(a.copy(notes = "new notes")))
        assertNotEquals(EpisodeMerge.fingerprint(a), EpisodeMerge.fingerprint(a.copy(durationMs = 1)))
        // Field boundaries matter: moving text between fields changes the fingerprint.
        assertNotEquals(
            EpisodeMerge.fingerprint(a.copy(title = "ab", link = null)),
            EpisodeMerge.fingerprint(a.copy(title = "a", link = "b")),
        )
    }

    @Test
    fun `merging 10,000 items against 10,000 stored ones is fast`() {
        val store = Store()
        val items = (1..10_000).map { ep(it) }
        store.merge(items, now)
        val took = measureTime { assertEquals(10_000, store.merge(items, now + day).unchanged) }
        assertTrue("took $took", took < 5.seconds)
    }

    @Test
    fun `stored rows never show their enclosure query in toString`() {
        val row = StoredEpisode(1, "g", "https://m.example.com/a.mp3?token=s3cr3t", "t", now, null, true)
        assertFalse(row.toString().contains("s3cr3t"))
    }
}
