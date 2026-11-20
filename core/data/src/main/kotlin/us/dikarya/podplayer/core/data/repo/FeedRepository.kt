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
import us.dikarya.podplayer.core.data.db.FeedSettingsEntity
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
import us.dikarya.podplayer.core.net.Validators
import us.dikarya.podplayer.core.net.redact
import us.dikarya.podplayer.core.net.scrubUrls

sealed interface SubscribeResult {
    data class Subscribed(val feedId: Long) : SubscribeResult

    data class AlreadySubscribed(val feedId: Long) : SubscribeResult

    data class Refused(val problem: UrlProblem) : SubscribeResult
}

sealed interface KeepAsideResult {
    data class Stored(val feedId: Long) : KeepAsideResult

    data class Refused(val problem: UrlProblem) : KeepAsideResult
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

/** What a refresh needs to know about one feed. The URL is a secret: [toString] redacts it. */
class RefreshTarget(
    val feedId: Long,
    val url: String,
    val ownerApprovedHost: String?,
    /** From the last good response, for a conditional GET (SPEC §3). */
    val validators: Validators,
    val consecutiveFailures: Int,
    val nextRefreshAt: Long?,
) {
    override fun toString() = "RefreshTarget($feedId, ${redact(url)}, failures=$consecutiveFailures)"
}

/** The outcome of [FeedRepository.moveUrl]. */
sealed interface MoveResult {
    data object Moved : MoveResult

    data object Unchanged : MoveResult

    /** Another subscription already has that URL; the feed keeps its old one. */
    data class AlreadySubscribed(val otherFeedId: Long) : MoveResult

    /** The new URL fails the URL policy. */
    data object Refused : MoveResult

    data object FeedGone : MoveResult
}

class FeedRepository internal constructor(private val db: PodDatabase, private val clock: () -> Long) {
    /** Tests only: runs inside the transaction after the inserts, to prove a failure rolls everything back. */
    internal var faultAfterInserts: (() -> Unit)? = null

    /** The library: subscribed shows. */
    fun feeds(): Flow<List<Feed>> = db.feeds().observeSubscribed().map { rows -> rows.map { it.toModel() } }

    /** Every show, kept-aside ones too: for naming an episode's show in lists (queue, starred, downloads). */
    fun allFeeds(): Flow<List<Feed>> = db.feeds().observeAll().map { rows -> rows.map { it.toModel() } }

    fun feed(id: Long): Flow<Feed?> = db.feeds().observe(id).map { it?.toModel() }

    suspend fun get(id: Long): Feed? = db.feeds().get(id)?.toModel()

    /** Each feed's newest episode date, by feed id; feeds without episodes are absent. */
    fun newestEpisodeDates(): Flow<Map<Long, Long>> =
        db.episodes().observeNewestPerFeed().map { rows -> rows.associate { it.feedId to it.newest } }

    /**
     * Adds a subscription. The URL goes through the URL policy and is stored in canonical form.
     * [ownerTyped]: the owner typed or pasted this URL, so its host is approved even on a private network
     * (SPEC §3). Feeds from search or OPML import pass false. [autoDownload]: the owner's default for new shows
     * (SPEC §9); a new subscription gets it from this moment, an existing one keeps its own.
     */
    suspend fun subscribe(url: String, ownerTyped: Boolean, autoDownload: Boolean = false): SubscribeResult {
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
                if (existing.subscribed) {
                    SubscribeResult.AlreadySubscribed(existing.id)
                } else {
                    // A show kept aside from a preview becomes a subscription, keeping its episodes and positions.
                    val now = clock()
                    db.feeds().markSubscribed(existing.id, now)
                    if (autoDownload) {
                        val settings = db.feedSettings().get(existing.id) ?: FeedSettingsEntity(existing.id)
                        db.feedSettings().upsert(settings.copy(autoDownloadSince = now))
                    }
                    SubscribeResult.Subscribed(existing.id)
                }
            } else {
                val now = clock()
                val id = db.feeds().insert(FeedEntity(url = canonical, ownerApprovedHost = host, subscribedAt = now))
                if (autoDownload) db.feedSettings().upsert(FeedSettingsEntity(id, autoDownloadSince = now))
                SubscribeResult.Subscribed(id)
            }
        }
    }

    /**
     * Removes the feed with its episodes, queue entries, positions and settings (foreign keys cascade).
     * Downloaded files are not touched here; `:core:jobs` removes them first.
     */
    suspend fun unsubscribe(id: Long) {
        db.feeds().delete(id)
    }

    /**
     * Stores a previewed show without subscribing, so the owner can play, queue, star or download its episodes
     * (owner, 2026-11-18). [parsed] is the preview already fetched; it is applied like a refresh. The row is not
     * owner-typed (no approved host) and stays out of the library, Home, refreshes and auto-download. A show that
     * is already stored, kept aside or subscribed, keeps its row and gets [parsed] applied. Returns the feed id.
     */
    suspend fun keepAside(url: String, parsed: ParsedFeed): KeepAsideResult {
        val canonical = when (val check = UrlPolicy.checkFeedUrl(url)) {
            is UrlCheck.Allowed -> check.url.toString()
            is UrlCheck.Rejected -> return KeepAsideResult.Refused(check.problem)
        }
        return db.withTransaction {
            val now = clock()
            val existing = db.feeds().getByUrl(canonical)
            val id = if (existing != null) {
                db.feeds().touchKeptAside(existing.id, now)
                existing.id
            } else {
                db.feeds().insert(FeedEntity(url = canonical, subscribedAt = now, subscribed = false))
            }
            applyRefresh(id, parsed)
            KeepAsideResult.Stored(id)
        }
    }

    /**
     * Removes kept-aside shows nothing uses any more (see FeedDao.unusedKeptAsideIds); [loadedEpisodeId] is the
     * episode in the player. A show kept aside or played within [KEPT_ASIDE_GRACE_MS] stays, so clean-up never
     * pulls a show from under a page the owner just opened. A show with any download stays too, so no file is
     * left behind. Returns the number removed.
     */
    suspend fun removeUnusedKeptAside(loadedEpisodeId: Long?): Int = db.withTransaction {
        val ids = db.feeds().unusedKeptAsideIds(loadedEpisodeId, before = clock() - KEPT_ASIDE_GRACE_MS)
        ids.forEach { db.feeds().delete(it) }
        ids.size
    }

    suspend fun refreshTarget(id: Long): RefreshTarget? = db.feeds().get(id)?.let {
        RefreshTarget(it.id, it.url, it.ownerApprovedHost, Validators.of(it.etag, it.lastModified), it.consecutiveFailures, it.nextRefreshAt)
    }

    suspend fun allFeedIds(): List<Long> = db.feeds().allIds()

    /** Feeds the periodic refresh should fetch at [now]: all but those backing off (SPEC §8). */
    suspend fun dueFeedIds(now: Long): List<Long> = db.feeds().dueIds(now)

    /**
     * A good refresh: resets the backoff. [validators] null (a 304) keeps the stored ones; otherwise they
     * replace them, so a server that stopped sending an ETag isn't asked with a stale one.
     */
    suspend fun recordRefreshSuccess(id: Long, validators: Validators?, at: Long) {
        if (validators == null) {
            db.feeds().recordNotModified(id, at)
        } else {
            db.feeds().recordSuccess(id, validators.etag, validators.lastModified, at)
        }
    }

    /**
     * A failed refresh. [nextAttemptAt] gets the new count of consecutive failures and returns when the feed is
     * next due. [message] is plain language; it is redacted and capped here. Returns the new count.
     */
    suspend fun recordRefreshFailure(id: Long, message: String, at: Long, nextAttemptAt: (failures: Int) -> Long): Int =
        db.withTransaction {
            val feed = db.feeds().get(id) ?: return@withTransaction 0
            val failures = (feed.consecutiveFailures + 1).coerceAtMost(MAX_COUNTED_FAILURES)
            db.feeds().recordFailure(id, scrubUrls(message).take(MAX_ERROR), at, failures, nextAttemptAt(failures))
            failures
        }

    /**
     * SPEC §3: a 301/308 on a feed URL moves the subscription. The owner-approved host stays as it was: it
     * only ever approves the host the owner typed.
     */
    suspend fun moveUrl(id: Long, newUrl: String): MoveResult {
        val allowed = when (val check = UrlPolicy.checkFeedUrl(newUrl)) {
            is UrlCheck.Allowed -> check.url.toString()
            is UrlCheck.Rejected -> return MoveResult.Refused
        }
        return db.withTransaction {
            val feed = db.feeds().get(id) ?: return@withTransaction MoveResult.FeedGone
            if (feed.url == allowed) return@withTransaction MoveResult.Unchanged
            val other = db.feeds().getByUrl(allowed)
            if (other != null) {
                // A show kept aside from a preview gives way to the subscription only while it holds nothing of the
                // owner's: deleting it cascades to its stars, queue entries, positions and played marks, and would
                // leave a download's files behind (CodeRabbit review, PR 3).
                if (other.subscribed || db.feeds().holdsOwnerData(other.id)) {
                    return@withTransaction MoveResult.AlreadySubscribed(other.id)
                }
                db.feeds().delete(other.id)
            }
            db.feeds().setUrl(id, allowed)
            MoveResult.Moved
        }
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

    companion object {
        /** How long a show kept aside from a preview stays after it was last opened or played: a week. */
        const val KEPT_ASIDE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

        /** Keeps every statement under SQLite's 999-variable limit on old Android versions. */
        private const val CHUNK = 500
        private const val MAX_ERROR = 2_000
        private const val MAX_COUNTED_FAILURES = 1_000
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
