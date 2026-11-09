/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import us.dikarya.podplayer.core.data.db.EpisodeEntity
import us.dikarya.podplayer.core.data.db.EpisodeFeedFields
import us.dikarya.podplayer.core.data.db.FeedChannelFields
import us.dikarya.podplayer.core.data.db.FeedEntity
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.ParsedFeed
import us.dikarya.podplayer.core.feed.identity.EpisodeMerge
import us.dikarya.podplayer.core.feed.identity.StoredEpisode
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.core.model.assertInvariant
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.UrlProblem

sealed interface SubscribeResult {
    data class Subscribed(val feedId: Long) : SubscribeResult

    data class AlreadySubscribed(val feedId: Long) : SubscribeResult

    data class Refused(val problem: UrlProblem) : SubscribeResult
}

sealed interface RefreshApplied {
    data class Applied(
        val inserted: Int,
        val updated: Int,
        /** Of [updated]: same episode, newer date. */
        val reposted: Int,
        val noLongerInFeed: Int,
        val backInFeed: Int,
        val unchanged: Int,
        val skippedWithoutEnclosure: Int,
        val duplicatesDropped: Int,
    ) : RefreshApplied

    /** The feed was unsubscribed while it was being fetched. */
    data object FeedGone : RefreshApplied
}

class FeedRepository internal constructor(private val db: PodDatabase, private val clock: () -> Long) {
    /** Tests only: runs inside the transaction after the inserts, to prove a failure rolls everything back. */
    internal var faultAfterInserts: (() -> Unit)? = null

    fun feeds(): Flow<List<Feed>> = db.feeds().observeAll().map { rows -> rows.map { it.toModel() } }

    fun feed(id: Long): Flow<Feed?> = db.feeds().observe(id).map { it?.toModel() }

    suspend fun get(id: Long): Feed? = db.feeds().get(id)?.toModel()

    /**
     * Adds a subscription. The URL goes through the URL policy and is stored in canonical form.
     * [ownerTyped]: the owner typed or pasted this URL, so its host is approved even on a private network
     * (SPEC §3). Feeds from search or OPML import pass false.
     */
    suspend fun subscribe(url: String, ownerTyped: Boolean): SubscribeResult {
        val allowed = when (val check = UrlPolicy.checkFeedUrl(url)) {
            is UrlCheck.Allowed -> check.url
            is UrlCheck.Rejected -> return SubscribeResult.Refused(check.problem)
        }
        val canonical = allowed.toString()
        val host = allowed.host.takeIf { ownerTyped }
        return db.withTransaction {
            val existing = db.feeds().getByUrl(canonical)
            if (existing != null) {
                if (host != null && existing.ownerApprovedHost == null) {
                    db.feeds().setOwnerApprovedHost(existing.id, host)
                }
                SubscribeResult.AlreadySubscribed(existing.id)
            } else {
                SubscribeResult.Subscribed(
                    db.feeds().insert(FeedEntity(url = canonical, ownerApprovedHost = host, subscribedAt = clock())),
                )
            }
        }
    }

    /** Removes the feed with its episodes, queue entries, positions and settings (foreign keys cascade). */
    suspend fun unsubscribe(id: Long) {
        db.feeds().delete(id)
    }

    /**
     * Applies one successful refresh in a single transaction (SPEC §4, LESSONS "Episode identity"): runs the
     * episode merge against what is stored and writes inserts, metadata updates, "no longer in feed" and
     * "back in feed", and the channel fields. Updates never touch played state, positions, the queue or
     * downloads, which live in other tables. If anything fails, nothing is applied and the last good copy stays.
     */
    suspend fun applyRefresh(feedId: Long, parsed: ParsedFeed): RefreshApplied = db.withTransaction {
        val feed = db.feeds().get(feedId) ?: return@withTransaction RefreshApplied.FeedGone
        val now = clock()
        val rows = db.episodes().mergeRows(feedId)
        val stored = rows.map { StoredEpisode(it.id, it.guid, it.enclosureUrl, it.title, it.publishedAt, it.fingerprint, it.inFeed) }
        val changes = EpisodeMerge.merge(stored, parsed.episodes, now)

        val inserts = changes.inserts.mapNotNull { new -> new.episode.toEntity(feedId, new.publishedAt, now, new.fingerprint) }
        inserts.chunked(CHUNK).forEach { db.episodes().insertAll(it) }
        faultAfterInserts?.invoke()

        val byId = rows.associateBy { it.id }
        val updates = changes.updates.mapNotNull { update ->
            val row = byId[update.id] ?: return@mapNotNull null
            update.episode.toFeedFields(
                id = update.id,
                // SPEC §4 and EpisodeUpdate: a missing date keeps the stored one, a missing GUID keeps the old GUID.
                publishedAt = update.episode.publishedAt ?: row.publishedAt,
                guid = update.episode.guid ?: row.guid,
                fingerprint = update.fingerprint,
            )
        }
        updates.chunked(CHUNK).forEach { db.episodes().updateFromFeed(it) }
        changes.noLongerInFeed.chunked(CHUNK).forEach { db.episodes().markNotInFeed(it, now) }
        changes.backInFeed.chunked(CHUNK).forEach { db.episodes().markBackInFeed(it) }

        // A field the feed left out keeps its last value rather than blanking the show.
        val extras = FeedExtras.of(parsed)
        db.feeds().updateChannel(
            FeedChannelFields(
                id = feedId,
                title = parsed.title ?: feed.title,
                link = parsed.link ?: feed.link,
                description = parsed.description ?: feed.description,
                author = parsed.author ?: feed.author,
                imageUrl = parsed.imageUrl ?: feed.imageUrl,
                categoriesJson = encodeStrings(parsed.categories) ?: feed.categoriesJson,
                podcastGuid = parsed.podcastGuid ?: feed.podcastGuid,
                extrasJson = FeedExtras.encode(extras) ?: feed.extrasJson,
            ),
        )
        RefreshApplied.Applied(
            inserted = inserts.size,
            updated = updates.size,
            reposted = changes.updates.count { it.reposted },
            noLongerInFeed = changes.noLongerInFeed.size,
            backInFeed = changes.backInFeed.size,
            unchanged = changes.unchanged,
            skippedWithoutEnclosure = changes.skippedWithoutEnclosure,
            duplicatesDropped = changes.duplicatesDropped,
        )
    }

    private companion object {
        /** Keeps every statement under SQLite's 999-variable limit on old Android versions. */
        const val CHUNK = 500
    }
}

private fun ParsedEpisode.toEntity(feedId: Long, publishedAt: Long, now: Long, fingerprint: String): EpisodeEntity? {
    val enclosure = enclosure
    assertInvariant(enclosure != null) { "merge returned an item without an enclosure" }
    if (enclosure == null) return null
    return EpisodeEntity(
        feedId = feedId,
        guid = guid,
        title = title,
        publishedAt = publishedAt,
        firstSeenAt = now,
        enclosureUrl = enclosure.url,
        enclosureType = enclosure.type,
        enclosureLength = enclosure.length,
        durationMs = durationMs,
        notes = notes,
        imageUrl = imageUrl,
        link = link,
        season = season,
        episodeNumber = episode,
        chaptersUrl = chapters?.url,
        chaptersType = chapters?.type,
        extrasJson = EpisodeExtras.encode(EpisodeExtras.of(this)),
        fingerprint = fingerprint,
    )
}

private fun ParsedEpisode.toFeedFields(id: Long, publishedAt: Long, guid: String?, fingerprint: String): EpisodeFeedFields? {
    val enclosure = enclosure ?: return null
    return EpisodeFeedFields(
        id = id,
        guid = guid,
        title = title,
        publishedAt = publishedAt,
        enclosureUrl = enclosure.url,
        enclosureType = enclosure.type,
        enclosureLength = enclosure.length,
        durationMs = durationMs,
        notes = notes,
        imageUrl = imageUrl,
        link = link,
        season = season,
        episodeNumber = episode,
        chaptersUrl = chapters?.url,
        chaptersType = chapters?.type,
        extrasJson = EpisodeExtras.encode(EpisodeExtras.of(this)),
        fingerprint = fingerprint,
    )
}
