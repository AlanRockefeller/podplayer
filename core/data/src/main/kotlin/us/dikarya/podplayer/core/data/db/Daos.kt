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

    @Query("SELECT * FROM episodes WHERE inFeed = 1 ORDER BY publishedAt DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT COUNT(*) FROM episodes WHERE feedId = :feedId")
    suspend fun countForFeed(feedId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM episodes WHERE id = :id)")
    suspend fun exists(id: Long): Boolean
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

    @Query("DELETE FROM refresh_log WHERE id NOT IN (SELECT id FROM refresh_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimRefresh(keep: Int): Int

    @Query("DELETE FROM download_log WHERE id NOT IN (SELECT id FROM download_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimDownloads(keep: Int): Int
}
