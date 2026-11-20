/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What the episode merge needs from each stored episode of a feed. */
data class MergeRow(
    val id: Long,
    val guid: String?,
    val enclosureUrl: String,
    val title: String?,
    val publishedAt: Long,
    val fingerprint: String,
    val inFeed: Boolean,
)

/** The columns a refresh may change. Played state, position, queue and downloads live in other tables. */
data class EpisodeFeedFields(
    val id: Long,
    val guid: String?,
    val title: String?,
    val publishedAt: Long,
    val enclosureUrl: String,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val durationMs: Long?,
    val notes: String?,
    val imageUrl: String?,
    val link: String?,
    val season: Int?,
    val episodeNumber: Int?,
    val chaptersUrl: String?,
    val chaptersType: String?,
    val extrasJson: String?,
    val fingerprint: String,
)

/** A feed's newest episode date (Library's sort). */
data class FeedNewest(val feedId: Long, val newest: Long)

/** The columns a refresh may change on a feed. */
data class FeedChannelFields(
    val id: Long,
    val title: String?,
    val link: String?,
    val description: String?,
    val author: String?,
    val imageUrl: String?,
    val categoriesJson: String?,
    val podcastGuid: String?,
    val extrasJson: String?,
)

@Dao
interface FeedDao {
    /** The library: subscribed shows only. */
    @Query("SELECT * FROM feeds WHERE subscribed = 1 ORDER BY title COLLATE NOCASE, id")
    fun observeSubscribed(): Flow<List<FeedEntity>>

    /** Every show, kept-aside ones too: for naming the show of an episode wherever it is listed. */
    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE, id")
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds WHERE id = :id")
    fun observe(id: Long): Flow<FeedEntity?>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun get(id: Long): FeedEntity?

    @Query("SELECT * FROM feeds WHERE url = :url")
    suspend fun getByUrl(url: String): FeedEntity?

    @Query("SELECT * FROM feeds ORDER BY id")
    suspend fun all(): List<FeedEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(feed: FeedEntity): Long

    @Update(entity = FeedEntity::class)
    suspend fun updateChannel(fields: FeedChannelFields): Int

    @Query("UPDATE feeds SET ownerApprovedHost = :host WHERE id = :id")
    suspend fun setOwnerApprovedHost(id: Long, host: String?): Int

    /** Subscribed shows: what "refresh all" fetches. A kept-aside show is never fetched in the background. */
    @Query("SELECT id FROM feeds WHERE subscribed = 1 ORDER BY id")
    suspend fun allIds(): List<Long>

    /** Subscribed feeds not backing off: never failed, or their retry time has come. */
    @Query("SELECT id FROM feeds WHERE subscribed = 1 AND (nextRefreshAt IS NULL OR nextRefreshAt <= :now) ORDER BY id")
    suspend fun dueIds(now: Long): List<Long>

    /** Turns a kept-aside show into a subscription; returns the rows changed (0 when it already was one). */
    @Query("UPDATE feeds SET subscribed = 1, subscribedAt = :at WHERE id = :id AND subscribed = 0")
    suspend fun markSubscribed(id: Long, at: Long): Int

    /** A kept-aside show was opened again: [at] becomes its "kept aside at" time (subscribedAt). */
    @Query("UPDATE feeds SET subscribedAt = :at WHERE id = :id AND subscribed = 0")
    suspend fun touchKeptAside(id: Long, at: Long): Int

    /**
     * Kept-aside shows nothing is using any more: kept aside and last played before [before], and no episode
     * queued, with a download (any state, so no file is left behind), starred, started and unfinished, or loaded in
     * the player ([loadedEpisodeId]).
     */
    @Query(
        """SELECT f.id FROM feeds f WHERE f.subscribed = 0 AND f.subscribedAt < :before AND NOT EXISTS (
             SELECT 1 FROM episodes e WHERE e.feedId = f.id AND (
               e.starred = 1 OR e.id = :loadedEpisodeId
               OR EXISTS (SELECT 1 FROM queue_entries q WHERE q.episodeId = e.id)
               OR EXISTS (SELECT 1 FROM media_files m WHERE m.episodeId = e.id)
               OR EXISTS (SELECT 1 FROM playback_records p WHERE p.episodeId = e.id
                          AND ((p.played = 0 AND p.positionMs > 0) OR p.updatedAt >= :before))))
           ORDER BY f.id""",
    )
    suspend fun unusedKeptAsideIds(loadedEpisodeId: Long?, before: Long): List<Long>

    /** True when any episode of [feedId] holds owner data: a star, a queue entry, a download, or a playback record. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM episodes e WHERE e.feedId = :feedId AND (
             e.starred = 1
             OR EXISTS (SELECT 1 FROM queue_entries q WHERE q.episodeId = e.id)
             OR EXISTS (SELECT 1 FROM media_files m WHERE m.episodeId = e.id)
             OR EXISTS (SELECT 1 FROM playback_records p WHERE p.episodeId = e.id)))""",
    )
    suspend fun holdsOwnerData(feedId: Long): Boolean

    @Query(
        """UPDATE feeds SET etag = :etag, lastModified = :lastModified, lastRefreshSuccessAt = :at,
           consecutiveFailures = 0, nextRefreshAt = NULL WHERE id = :id""",
    )
    suspend fun recordSuccess(id: Long, etag: String?, lastModified: String?, at: Long): Int

    /** A 304: the stored validators are still the right ones. */
    @Query("UPDATE feeds SET lastRefreshSuccessAt = :at, consecutiveFailures = 0, nextRefreshAt = NULL WHERE id = :id")
    suspend fun recordNotModified(id: Long, at: Long): Int

    @Query(
        """UPDATE feeds SET lastRefreshErrorAt = :at, lastRefreshError = :message, consecutiveFailures = :failures,
           nextRefreshAt = :nextAt WHERE id = :id""",
    )
    suspend fun recordFailure(id: Long, message: String, at: Long, failures: Int, nextAt: Long): Int

    @Query("UPDATE feeds SET url = :url WHERE id = :id")
    suspend fun setUrl(id: Long, url: String): Int

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
interface EpisodeDao {
    @Query("SELECT id, guid, enclosureUrl, title, publishedAt, fingerprint, inFeed FROM episodes WHERE feedId = :feedId")
    suspend fun mergeRows(feedId: Long): List<MergeRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(episodes: List<EpisodeEntity>): List<Long>

    @Update(entity = EpisodeEntity::class)
    suspend fun updateFromFeed(fields: List<EpisodeFeedFields>): Int

    @Query("UPDATE episodes SET inFeed = 0, removedFromFeedAt = :at WHERE id IN (:ids)")
    suspend fun markNotInFeed(ids: List<Long>, at: Long): Int

    @Query("UPDATE episodes SET inFeed = 1, removedFromFeedAt = NULL WHERE id IN (:ids)")
    suspend fun markBackInFeed(ids: List<Long>): Int

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observe(id: Long): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE feedId = :feedId ORDER BY publishedAt DESC, id DESC")
    fun observeForFeed(feedId: Long): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT e.* FROM episodes e JOIN feeds f ON f.id = e.feedId WHERE e.inFeed = 1 AND f.subscribed = 1
           ORDER BY e.publishedAt DESC, e.id DESC LIMIT :limit""",
    )
    fun observeLatest(limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT feedId, MAX(publishedAt) AS newest FROM episodes GROUP BY feedId")
    fun observeNewestPerFeed(): Flow<List<FeedNewest>>

    @Query("SELECT COUNT(*) FROM episodes WHERE feedId = :feedId")
    suspend fun countForFeed(feedId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM episodes WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    /** [starredAt] is null exactly when [starred] is false (StarredRepository). */
    @Query("UPDATE episodes SET starred = :starred, starredAt = :starredAt WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean, starredAt: Long?): Int

    /**
     * Starred episodes, newest starred first. [pattern] is a LIKE pattern with `\` as the escape character, or
     * null for every one. SQLite's LIKE ignores case for ASCII letters only.
     */
    @Query(
        """SELECT e.* FROM episodes e JOIN feeds f ON f.id = e.feedId
           WHERE e.starred = 1 AND (:pattern IS NULL OR e.title LIKE :pattern ESCAPE '\' OR f.title LIKE :pattern ESCAPE '\')
           ORDER BY e.starredAt DESC, e.id DESC LIMIT :limit""",
    )
    fun observeStarred(pattern: String?, limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT id FROM episodes WHERE feedId = :feedId AND starred = 1")
    fun observeStarredIds(feedId: Long): Flow<List<Long>>

    /**
     * Episodes with a download that hasn't failed: ones still on their way first (latest change first), then
     * finished ones, newest finished first.
     */
    @Query(
        """SELECT e.* FROM episodes e JOIN media_files m ON m.episodeId = e.id
           WHERE m.state != 'Failed'
           ORDER BY m.state = 'Complete', COALESCE(m.completedAt, m.updatedAt) DESC, e.id DESC LIMIT :limit""",
    )
    fun observeDownloaded(limit: Int): Flow<List<EpisodeEntity>>
}

@Dao
interface MediaFileDao {
    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId")
    suspend fun get(episodeId: Long): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId")
    fun observe(episodeId: Long): Flow<MediaFileEntity?>

    @Query("SELECT * FROM media_files ORDER BY episodeId")
    suspend fun all(): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE state = 'Complete' ORDER BY completedAt DESC")
    fun observeComplete(): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files ORDER BY episodeId")
    fun observeAll(): Flow<List<MediaFileEntity>>

    @Query("SELECT m.* FROM media_files m JOIN episodes e ON e.id = m.episodeId WHERE e.feedId = :feedId ORDER BY m.episodeId")
    suspend fun forFeed(feedId: Long): List<MediaFileEntity>

    /** Bytes on disk for every download but one: what the storage cap leaves for that one. */
    @Query("SELECT COALESCE(SUM(bytesDownloaded), 0) FROM media_files WHERE episodeId != :exceptEpisodeId")
    suspend fun usedBytesExcept(exceptEpisodeId: Long): Long

    @Query("UPDATE media_files SET etag = :etag, lastModified = :lastModified WHERE episodeId = :episodeId")
    suspend fun setValidators(episodeId: Long, etag: String?, lastModified: String?): Int

    @Query(
        """UPDATE media_files SET state = 'Complete', bytesDownloaded = :bytes, totalBytes = :bytes,
           probedDurationMs = :durationMs, mimeType = :mimeType, completedAt = :at, updatedAt = :at
           WHERE episodeId = :episodeId""",
    )
    suspend fun markComplete(episodeId: Long, bytes: Long, durationMs: Long, mimeType: String?, at: Long): Int

    @Upsert
    suspend fun upsert(file: MediaFileEntity)

    @Query("DELETE FROM media_files WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long): Int
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_entries ORDER BY position, addedAt")
    fun observe(): Flow<List<QueueEntryEntity>>

    @Query("SELECT * FROM queue_entries ORDER BY position, addedAt")
    suspend fun all(): List<QueueEntryEntity>

    @Query("SELECT e.* FROM queue_entries q JOIN episodes e ON e.id = q.episodeId ORDER BY q.position, q.addedAt")
    fun observeEpisodes(): Flow<List<EpisodeEntity>>

    @Upsert
    suspend fun upsertAll(entries: List<QueueEntryEntity>)

    @Query("DELETE FROM queue_entries WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long): Int

    @Query("DELETE FROM queue_entries")
    suspend fun clear()
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_records WHERE episodeId = :episodeId")
    suspend fun get(episodeId: Long): PlaybackRecordEntity?

    @Query("SELECT * FROM playback_records WHERE episodeId = :episodeId")
    fun observe(episodeId: Long): Flow<PlaybackRecordEntity?>

    @Query("SELECT p.* FROM playback_records p JOIN episodes e ON e.id = p.episodeId WHERE e.feedId = :feedId")
    fun observeForFeed(feedId: Long): Flow<List<PlaybackRecordEntity>>

    /** Records of every episode with a download row: Library › Downloads, with no id list to cap. */
    @Query("SELECT p.* FROM playback_records p JOIN media_files m ON m.episodeId = p.episodeId")
    fun observeForDownloads(): Flow<List<PlaybackRecordEntity>>

    /** Callers keep [ids] under SQLite's variable limit. */
    @Query("SELECT * FROM playback_records WHERE episodeId IN (:ids)")
    fun observeFor(ids: List<Long>): Flow<List<PlaybackRecordEntity>>

    @Upsert
    suspend fun upsert(record: PlaybackRecordEntity)

    /** Started and not finished, most recent first: Home's Continue card and Android Auto's Continue. */
    @Query(
        """SELECT e.* FROM playback_records p JOIN episodes e ON e.id = p.episodeId
           WHERE p.played = 0 AND p.positionMs > 0 ORDER BY p.updatedAt DESC LIMIT :limit""",
    )
    fun observeInProgress(limit: Int): Flow<List<EpisodeEntity>>
}

@Dao
interface FeedSettingsDao {
    @Query("SELECT * FROM feed_settings WHERE feedId = :feedId")
    suspend fun get(feedId: Long): FeedSettingsEntity?

    @Query("SELECT * FROM feed_settings WHERE feedId = :feedId")
    fun observe(feedId: Long): Flow<FeedSettingsEntity?>

    @Upsert
    suspend fun upsert(settings: FeedSettingsEntity)
}

/** What auto-download and clean-up read (SPEC §9). See DownloadRulesRepository for the rules. */
@Dao
interface DownloadRulesDao {
    @Query(
        """SELECT e.* FROM episodes e JOIN feed_settings s ON s.feedId = e.feedId JOIN feeds f ON f.id = e.feedId
           WHERE f.subscribed = 1 AND s.autoDownloadSince IS NOT NULL AND e.publishedAt > s.autoDownloadSince AND e.inFeed = 1
             AND e.autoDownloadedAt IS NULL
             AND NOT EXISTS (SELECT 1 FROM media_files m WHERE m.episodeId = e.id)
             AND NOT EXISTS (SELECT 1 FROM playback_records p WHERE p.episodeId = e.id AND p.played = 1)
           ORDER BY e.publishedAt, e.id LIMIT :limit""",
    )
    suspend fun autoDownloadCandidates(limit: Int): List<EpisodeEntity>

    @Query(
        """SELECT e.* FROM episodes e
           WHERE e.starred = 1 AND e.autoDownloadedAt IS NULL
             AND NOT EXISTS (SELECT 1 FROM media_files m WHERE m.episodeId = e.id)
           ORDER BY e.starredAt DESC, e.id DESC LIMIT :limit""",
    )
    suspend fun starredNotDownloaded(limit: Int): List<EpisodeEntity>

    @Query("UPDATE episodes SET autoDownloadedAt = :at WHERE id = :id")
    suspend fun markAutoQueued(id: Long, at: Long): Int

    @Query(
        """SELECT m.episodeId AS episodeId, m.completedAt AS completedAt, COALESCE(p.played, 0) AS played,
             CASE WHEN p.played = 1 THEN p.updatedAt ELSE NULL END AS playedAt, e.starred AS starred,
             EXISTS (SELECT 1 FROM queue_entries q WHERE q.episodeId = m.episodeId) AS queued
           FROM media_files m JOIN episodes e ON e.id = m.episodeId
           LEFT JOIN playback_records p ON p.episodeId = m.episodeId
           WHERE m.state = 'Complete' AND (:episodeId IS NULL OR m.episodeId = :episodeId)
           ORDER BY m.episodeId""",
    )
    suspend fun cleanUpCandidates(episodeId: Long?): List<us.dikarya.podplayer.core.data.repo.CleanUpCandidate>

    @Query("SELECT COUNT(*) FROM playback_records WHERE played = 1")
    fun observePlayedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM episodes WHERE starred = 1")
    fun observeStarredCount(): Flow<Int>
}

@Dao
interface EpisodeActionDao {
    @Insert
    suspend fun insert(action: EpisodeActionEntity): Long

    @Query("SELECT * FROM episode_actions WHERE synced = 0 ORDER BY id LIMIT :limit")
    suspend fun unsynced(limit: Int): List<EpisodeActionEntity>

    @Query("UPDATE episode_actions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>): Int

    @Query("SELECT * FROM episode_actions ORDER BY id")
    suspend fun all(): List<EpisodeActionEntity>
}

@Dao
interface LogDao {
    @Insert
    suspend fun insertRefresh(entry: RefreshLogEntity): Long

    @Insert
    suspend fun insertDownload(entry: DownloadLogEntity): Long

    @Query("SELECT * FROM refresh_log WHERE feedId = :feedId ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRefresh(feedId: Long, limit: Int): Flow<List<RefreshLogEntity>>

    @Query("SELECT * FROM refresh_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeAllRefresh(limit: Int): Flow<List<RefreshLogEntity>>

    @Query("SELECT * FROM download_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeDownloads(limit: Int): Flow<List<DownloadLogEntity>>

    @Query("SELECT * FROM download_log WHERE episodeId = :episodeId ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeDownloadsFor(episodeId: Long, limit: Int): Flow<List<DownloadLogEntity>>

    @Query("SELECT * FROM download_log WHERE event = :event AND id > :afterId AND at >= :since ORDER BY id ASC LIMIT :limit")
    suspend fun downloadsAfter(event: DownloadEvent, afterId: Long, since: Long, limit: Int): List<DownloadLogEntity>

    @Query("DELETE FROM refresh_log WHERE id NOT IN (SELECT id FROM refresh_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimRefresh(keep: Int): Int

    @Query("DELETE FROM download_log WHERE id NOT IN (SELECT id FROM download_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimDownloads(keep: Int): Int

    @Insert
    suspend fun insertPosition(entry: PositionLogEntity): Long

    @Query("SELECT * FROM position_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun observePositions(limit: Int): Flow<List<PositionLogEntity>>

    @Query("DELETE FROM position_log WHERE id NOT IN (SELECT id FROM position_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimPositions(keep: Int): Int
}
