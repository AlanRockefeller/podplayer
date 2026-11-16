/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PositionCause
import us.dikarya.podplayer.core.net.redact

/*
 * The SPEC §7 tables. Changing anything here changes the schema: bump PodDatabase.VERSION, add a
 * migration to Migrations.ALL and a seed-and-check to MigrationTest (SchemaHistoryTest enforces it).
 * Enum columns hold the constant's name: renaming a constant needs a migration too.
 *
 * URL columns are secrets (private feeds carry tokens), so every toString() redacts them.
 */

@Entity(tableName = "feeds", indices = [Index(value = ["url"], unique = true)])
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The current feed URL, canonical form from the URL policy. */
    val url: String,
    /** The host of a feed URL the owner typed (SPEC §3): the only host this feed may reach on a private network. */
    val ownerApprovedHost: String? = null,
    val title: String? = null,
    val link: String? = null,
    /** Raw; sanitized at display time. */
    val description: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    /** JSON array of strings. */
    val categoriesJson: String? = null,
    val podcastGuid: String? = null,
    /** JSON: funding, persons and license ([FeedExtras]). */
    val extrasJson: String? = null,
    val subscribedAt: Long,
    /** Conditional-GET validators from the last good response (SPEC §3). */
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshSuccessAt: Long? = null,
    val lastRefreshErrorAt: Long? = null,
    /** Plain language, redacted. */
    val lastRefreshError: String? = null,
    val consecutiveFailures: Int = 0,
    val nextRefreshAt: Long? = null,
) {
    override fun toString() =
        "FeedEntity(id=$id, url=${redact(url)}, title=$title, ownerApprovedHost=$ownerApprovedHost)"
}

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["feedId", "publishedAt"]), Index(value = ["feedId", "guid"]), Index(value = ["starred", "starredAt"])],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val guid: String?,
    val title: String?,
    /** The feed's date, or the first-seen time when it had none. */
    val publishedAt: Long,
    val firstSeenAt: Long,
    val enclosureUrl: String,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val durationMs: Long?,
    /** Raw HTML; sanitized at display time. */
    val notes: String?,
    val imageUrl: String?,
    val link: String?,
    val season: Int?,
    val episodeNumber: Int?,
    val chaptersUrl: String?,
    val chaptersType: String?,
    /** JSON: transcripts, soundbites, alternate enclosures ([EpisodeExtras]); stored for later milestones. */
    val extrasJson: String?,
    /** `EpisodeMerge.fingerprint` of the item last merged into this row. */
    val fingerprint: String,
    val inFeed: Boolean = true,
    val removedFromFeedAt: Long? = null,
    /**
     * The owner's star (SPEC §7, v3). Set only by StarredRepository, which keeps [starredAt] non-null exactly
     * when [starred] is true; a refresh never writes either (EpisodeFeedFields leaves them out).
     */
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    val starredAt: Long? = null,
    /**
     * When auto-download queued this episode (SPEC §9, v4), so it is never fetched automatically twice: not after
     * clean-up deleted it, nor after the owner did. Set only by DownloadRulesRepository; a refresh never writes it.
     */
    val autoDownloadedAt: Long? = null,
) {
    override fun toString() =
        "EpisodeEntity(id=$id, feedId=$feedId, guid=$guid, title=$title, enclosure=${redact(enclosureUrl)}, inFeed=$inFeed, starred=$starred)"
}

/** A download. Media lives in app-private storage; [relativePath] is relative to the files folder (SPEC §7). */
@Entity(
    tableName = "media_files",
    foreignKeys = [
        ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MediaFileEntity(
    @PrimaryKey val episodeId: Long,
    val relativePath: String?,
    val state: DownloadState,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    /** If-Range validators for resuming (SPEC §9). */
    val etag: String? = null,
    val lastModified: String? = null,
    val probedDurationMs: Long? = null,
    val mimeType: String? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "queue_entries",
    foreignKeys = [
        ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["position"])],
)
data class QueueEntryEntity(
    @PrimaryKey val episodeId: Long,
    /** 0-based and dense; QueueRepository renumbers on every change. */
    val position: Int,
    val addedAt: Long,
)

@Entity(
    tableName = "playback_records",
    foreignKeys = [
        ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["updatedAt"])],
)
data class PlaybackRecordEntity(
    @PrimaryKey val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long?,
    val played: Boolean,
    val updatedAt: Long,
)

/** Per-show settings. Skip intro/outro and ad-skip settings join later (M2). */
@Entity(
    tableName = "feed_settings",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FeedSettingsEntity(
    @PrimaryKey val feedId: Long,
    /** Null: use the global speed. */
    val playbackSpeed: Float? = null,
    /**
     * "Download new episodes" (SPEC §9, v4): when the owner turned it on, or null when it is off. Only episodes
     * published after this time are downloaded automatically.
     */
    val autoDownloadSince: Long? = null,
)

/** Append-only history for M2 sync. Deleting the episode keeps the action (episodeId becomes null). */
@Entity(
    tableName = "episode_actions",
    foreignKeys = [
        ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["episodeId"]), Index(value = ["synced"])],
)
data class EpisodeActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long?,
    val kind: EpisodeActionKind,
    val positionMs: Long?,
    val durationMs: Long?,
    val at: Long,
    val deviceId: String,
    val synced: Boolean = false,
)

enum class DownloadEvent { Queued, Started, Resumed, Restarted, Completed, Failed, Cancelled, Deleted }

/** The per-download log the owner can read (SPEC §9). Messages are redacted before they get here. */
@Entity(
    tableName = "download_log",
    foreignKeys = [
        ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["episodeId"]), Index(value = ["at"])],
)
data class DownloadLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val at: Long,
    val event: DownloadEvent,
    val message: String?,
)

/**
 * The position log (SPEC §10: every position change with its cause). [episodeId] deliberately has no foreign
 * key: the owner reads this log after a jump, and unsubscribing must not erase the evidence.
 */
@Entity(tableName = "position_log", indices = [Index(value = ["at"])])
data class PositionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val at: Long,
    val fromMs: Long,
    val toMs: Long,
    val cause: PositionCause,
    val streaming: Boolean,
)

enum class RefreshOutcome { Updated, NotModified, Failed }

/** One row per refresh attempt (SPEC §8). Messages are plain language and redacted. */
@Entity(
    tableName = "refresh_log",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["feedId"]), Index(value = ["at"])],
)
data class RefreshLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val at: Long,
    val outcome: RefreshOutcome,
    val message: String?,
    val newEpisodes: Int = 0,
    val updatedEpisodes: Int = 0,
)
