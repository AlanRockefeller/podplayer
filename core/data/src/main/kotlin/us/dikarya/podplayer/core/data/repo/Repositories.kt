/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import androidx.room.withTransaction
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.DownloadLogEntity
import us.dikarya.podplayer.core.data.db.EpisodeActionEntity
import us.dikarya.podplayer.core.data.db.FeedSettingsEntity
import us.dikarya.podplayer.core.data.db.MediaFileEntity
import us.dikarya.podplayer.core.data.db.PlaybackRecordEntity
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.db.PositionLogEntity
import us.dikarya.podplayer.core.data.db.QueueEntryEntity
import us.dikarya.podplayer.core.data.db.RefreshLogEntity
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.data.media.MediaPaths
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.EpisodeAction
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.model.PlaybackRecord
import us.dikarya.podplayer.core.model.PositionChange
import us.dikarya.podplayer.core.model.QueueEntry
import us.dikarya.podplayer.core.model.assertInvariant
import us.dikarya.podplayer.core.net.Validators
import us.dikarya.podplayer.core.net.scrubUrls

/** Every repository over one open database. */
class Repositories(
    db: PodDatabase,
    clock: () -> Long = System::currentTimeMillis,
    deviceId: suspend () -> String,
) {
    val feeds = FeedRepository(db, clock)
    val episodes = EpisodeRepository(db)
    val queue = QueueRepository(db, clock)
    val playback = PlaybackRepository(db, clock)
    val feedSettings = FeedSettingsRepository(db)
    val actions = EpisodeActionRepository(db, clock, deviceId)
    val media = MediaFileRepository(db)
    val logs = LogRepository(db, clock)
    val starred = StarredRepository(db, clock, deviceId)
}

class EpisodeRepository internal constructor(private val db: PodDatabase) {
    fun forFeed(feedId: Long): Flow<List<Episode>> = db.episodes().observeForFeed(feedId).map { rows -> rows.map { it.toModel() } }

    fun latest(limit: Int): Flow<List<Episode>> = db.episodes().observeLatest(limit).map { rows -> rows.map { it.toModel() } }

    fun observe(id: Long): Flow<Episode?> = db.episodes().observe(id).map { it?.toModel() }

    suspend fun get(id: Long): Episode? = db.episodes().get(id)?.toModel()
}

/**
 * Up Next. The database is the source of truth; the player's playlist is a projection of it (CLAUDE.md).
 * Positions are 0-based and dense after every change.
 */
class QueueRepository internal constructor(private val db: PodDatabase, private val clock: () -> Long) {
    fun entries(): Flow<List<QueueEntry>> = db.queue().observe().map { rows -> rows.map { it.toModel() } }

    fun episodes(): Flow<List<Episode>> = db.queue().observeEpisodes().map { rows -> rows.map { it.toModel() } }

    /** Adds at [index] (clamped), or at the end. An episode already queued moves instead. Unknown ids are ignored. */
    suspend fun add(episodeId: Long, index: Int? = null) = db.withTransaction {
        if (!db.episodes().exists(episodeId)) return@withTransaction
        val order = db.queue().all().map { it.episodeId }.toMutableList()
        order.remove(episodeId)
        order.add((index ?: order.size).coerceIn(0, order.size), episodeId)
        write(order)
    }

    suspend fun move(episodeId: Long, toIndex: Int) = db.withTransaction {
        val order = db.queue().all().map { it.episodeId }.toMutableList()
        if (!order.remove(episodeId)) return@withTransaction
        order.add(toIndex.coerceIn(0, order.size), episodeId)
        write(order)
    }

    /** One step up ([by] -1) or down (+1) from where it is now in the database; no change at either end. */
    suspend fun shift(episodeId: Long, by: Int) = db.withTransaction {
        val order = db.queue().all().map { it.episodeId }.toMutableList()
        val from = order.indexOf(episodeId)
        if (from < 0) return@withTransaction
        val to = (from + by).coerceIn(0, order.size - 1)
        if (to == from) return@withTransaction
        order.removeAt(from)
        order.add(to, episodeId)
        write(order)
    }

    /**
     * "Play next": right after [playingId] when it is the head of the queue (where playing puts it), else at the
     * top. Adds the episode if it isn't queued; the playing episode itself stays where it is.
     */
    suspend fun playNext(episodeId: Long, playingId: Long?) = db.withTransaction {
        if (!db.episodes().exists(episodeId) || episodeId == playingId) return@withTransaction
        val order = db.queue().all().map { it.episodeId }.toMutableList()
        order.remove(episodeId)
        val index = if (playingId != null && order.firstOrNull() == playingId) 1 else 0
        order.add(index, episodeId)
        write(order)
    }

    suspend fun remove(episodeId: Long) = db.withTransaction {
        if (db.queue().delete(episodeId) > 0) write(db.queue().all().map { it.episodeId })
    }

    suspend fun clear() = db.queue().clear()

    private suspend fun write(order: List<Long>) {
        val existing = db.queue().all().associateBy { it.episodeId }
        val now = clock()
        val entries = order.mapIndexed { i, id -> QueueEntryEntity(id, i, existing[id]?.addedAt ?: now) }
        assertInvariant(entries.map { it.episodeId }.toSet().size == entries.size) { "queue holds an episode twice" }
        db.queue().upsertAll(entries)
    }
}

/** Positions and played state (CLAUDE.md: never lose a position). */
class PlaybackRepository internal constructor(private val db: PodDatabase, private val clock: () -> Long) {
    fun record(episodeId: Long): Flow<PlaybackRecord?> = db.playback().observe(episodeId).map { it?.toModel() }

    suspend fun get(episodeId: Long): PlaybackRecord? = db.playback().get(episodeId)?.toModel()

    /**
     * Saves where the owner is. Keeps the played flag and, when [durationMs] is null, the known duration.
     * Returns false if the episode no longer exists (unsubscribed mid-play), which is not an error.
     */
    suspend fun savePosition(episodeId: Long, positionMs: Long, durationMs: Long?): Boolean = db.withTransaction {
        if (!db.episodes().exists(episodeId)) return@withTransaction false
        val old = db.playback().get(episodeId)
        db.playback().upsert(
            PlaybackRecordEntity(
                episodeId = episodeId,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs?.takeIf { it > 0 } ?: old?.durationMs,
                played = old?.played ?: false,
                updatedAt = clock(),
            ),
        )
        true
    }

    /** Marking played starts the episode over next time; marking unplayed keeps the position. */
    suspend fun setPlayed(episodeId: Long, played: Boolean): Boolean = db.withTransaction {
        if (!db.episodes().exists(episodeId)) return@withTransaction false
        val old = db.playback().get(episodeId)
        db.playback().upsert(
            PlaybackRecordEntity(
                episodeId = episodeId,
                positionMs = if (played) 0 else old?.positionMs ?: 0,
                durationMs = old?.durationMs,
                played = played,
                updatedAt = clock(),
            ),
        )
        true
    }

    /** Every record of [feedId]'s episodes, by episode id (the show page's played and progress marks). */
    fun recordsForFeed(feedId: Long): Flow<Map<Long, PlaybackRecord>> =
        db.playback().observeForFeed(feedId).map { rows -> rows.associate { it.episodeId to it.toModel() } }

    /** The records of up to [MAX_IDS] episodes, by episode id; the rest of a longer list is ignored. */
    fun records(ids: List<Long>): Flow<Map<Long, PlaybackRecord>> {
        if (ids.isEmpty()) return flowOf(emptyMap())
        assertInvariant(ids.size <= MAX_IDS) { "records() takes at most $MAX_IDS ids" }
        return db.playback().observeFor(ids.take(MAX_IDS)).map { rows -> rows.associate { it.episodeId to it.toModel() } }
    }

    /** Started, not finished, most recently touched first. */
    fun inProgress(limit: Int): Flow<List<Episode>> = db.playback().observeInProgress(limit).map { rows -> rows.map { it.toModel() } }

    companion object {
        /** Well under SQLite's 999-variable limit on old Android versions. */
        const val MAX_IDS = 500
    }
}

class FeedSettingsRepository internal constructor(private val db: PodDatabase) {
    /** The per-show speed, or null for the global default. */
    fun speed(feedId: Long): Flow<Float?> = db.feedSettings().observe(feedId).map { it?.playbackSpeed }

    /** Out-of-range speeds are clamped; NaN or null clears the override. */
    suspend fun setSpeed(feedId: Long, speed: Float?) = db.withTransaction {
        if (db.feeds().get(feedId) == null) return@withTransaction
        val value = speed?.takeUnless { it.isNaN() }?.coerceIn(MIN_SPEED, MAX_SPEED)
        val old = db.feedSettings().get(feedId) ?: FeedSettingsEntity(feedId)
        db.feedSettings().upsert(old.copy(playbackSpeed = value))
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
    }
}

/** The append-only action history for M2 sync (SPEC §7). */
class EpisodeActionRepository internal constructor(
    private val db: PodDatabase,
    private val clock: () -> Long,
    private val deviceId: suspend () -> String,
) {
    suspend fun append(episodeId: Long, kind: EpisodeActionKind, positionMs: Long?, durationMs: Long?) {
        db.episodeActions().insert(
            EpisodeActionEntity(
                episodeId = episodeId.takeIf { db.episodes().exists(it) },
                kind = kind,
                positionMs = positionMs?.coerceAtLeast(0),
                durationMs = durationMs,
                at = clock(),
                deviceId = deviceId(),
            ),
        )
    }

    suspend fun unsynced(limit: Int): List<EpisodeAction> = db.episodeActions().unsynced(limit).map { it.toModel() }

    suspend fun markSynced(ids: List<Long>) {
        ids.chunked(500).forEach { db.episodeActions().markSynced(it) }
    }
}

/** What [MediaFileRepository.reconcileWithDisk] did. */
data class Reconciliation(val droppedRecords: Int, val deletedFiles: Int)

/** Download records. The files themselves are handled by `:core:jobs` (M1-S5). */
class MediaFileRepository internal constructor(private val db: PodDatabase) {
    suspend fun get(episodeId: Long): MediaFile? = db.mediaFiles().get(episodeId)?.toModel()

    fun observe(episodeId: Long): Flow<MediaFile?> = db.mediaFiles().observe(episodeId).map { it?.toModel() }

    /** [MediaFile.relativePath] must be a safe relative path (SPEC §7); anything else is a programming error. */
    suspend fun upsert(file: MediaFile, now: Long = System.currentTimeMillis()) {
        val path = file.relativePath
        require(path == null || MediaPaths.isSafeRelative(path)) { "media paths must be relative" }
        val old = db.mediaFiles().get(file.episodeId)
        db.mediaFiles().upsert(
            MediaFileEntity(
                episodeId = file.episodeId,
                relativePath = file.relativePath,
                state = file.state,
                bytesDownloaded = file.bytesDownloaded,
                totalBytes = file.totalBytes,
                etag = old?.etag,
                lastModified = old?.lastModified,
                probedDurationMs = file.probedDurationMs,
                mimeType = old?.mimeType,
                completedAt = file.completedAt,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(episodeId: Long) {
        db.mediaFiles().delete(episodeId)
    }

    fun observeAll(): Flow<List<MediaFile>> = db.mediaFiles().observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun forFeed(feedId: Long): List<MediaFile> = db.mediaFiles().forFeed(feedId).map { it.toModel() }

    /** Bytes taken by every download except [exceptEpisodeId], for the storage cap (SPEC §9). */
    suspend fun usedBytes(exceptEpisodeId: Long): Long = db.mediaFiles().usedBytesExcept(exceptEpisodeId)

    /** The If-Range validators saved with a partial download, or [Validators.None]. */
    suspend fun validators(episodeId: Long): Validators =
        db.mediaFiles().get(episodeId)?.let { Validators.of(it.etag, it.lastModified) } ?: Validators.None

    suspend fun saveValidators(episodeId: Long, validators: Validators) {
        db.mediaFiles().setValidators(episodeId, validators.etag, validators.lastModified)
    }

    /** The file passed the Media3 probe (SPEC §9). [mimeType] is the probed audio type, not the feed's claim. */
    suspend fun markComplete(episodeId: Long, bytes: Long, durationMs: Long, mimeType: String?, at: Long) {
        db.mediaFiles().markComplete(episodeId, bytes, durationMs, mimeType?.take(MAX_MIME), at)
    }

    suspend fun mimeType(episodeId: Long): String? = db.mediaFiles().get(episodeId)?.mimeType

    /**
     * After a restore (backups never include media): drops records whose file is gone, whose path is unsafe,
     * or whose finished download isn't the size the record says (this phone's file under an id the restored
     * library gave to another episode), and deletes media files that no remaining record owns, so they don't
     * take space outside the storage cap.
     */
    suspend fun reconcileWithDisk(paths: MediaPaths): Reconciliation {
        val owned = HashSet<String>()
        var dropped = 0
        for (row in db.mediaFiles().all()) {
            val file = paths.resolve(row.relativePath)?.takeIf { it.isFile }
            val isTheRecordsFile = file != null &&
                (row.state != DownloadState.Complete || file.length() == row.bytesDownloaded)
            if (file != null && isTheRecordsFile) {
                owned += file.path
            } else {
                db.mediaFiles().delete(row.episodeId)
                dropped++
            }
        }
        var deleted = 0
        for (file in paths.mediaFiles()) {
            val path = try {
                file.canonicalPath
            } catch (_: IOException) {
                continue
            }
            if (path !in owned && file.delete()) deleted++
        }
        return Reconciliation(dropped, deleted)
    }

    private companion object {
        const val MAX_MIME = 100
    }
}

/** The refresh log (SPEC §8) and download log (SPEC §9). Every message is redacted and capped here. */
class LogRepository internal constructor(private val db: PodDatabase, private val clock: () -> Long) {
    suspend fun addRefresh(feedId: Long, outcome: RefreshOutcome, message: String?, newEpisodes: Int = 0, updatedEpisodes: Int = 0) {
        db.withTransaction {
            if (db.feeds().get(feedId) == null) return@withTransaction
            db.logs().insertRefresh(RefreshLogEntity(0, feedId, clock(), outcome, clean(message), newEpisodes, updatedEpisodes))
            db.logs().trimRefresh(KEEP_ROWS)
        }
    }

    suspend fun addDownload(episodeId: Long, event: DownloadEvent, message: String?) {
        db.withTransaction {
            if (!db.episodes().exists(episodeId)) return@withTransaction
            db.logs().insertDownload(DownloadLogEntity(0, episodeId, clock(), event, clean(message)))
            db.logs().trimDownloads(KEEP_ROWS)
        }
    }

    fun refreshLog(feedId: Long, limit: Int): Flow<List<RefreshLogEntity>> = db.logs().observeRefresh(feedId, limit)

    fun refreshLog(limit: Int): Flow<List<RefreshLogEntity>> = db.logs().observeAllRefresh(limit)

    fun downloadLog(limit: Int): Flow<List<DownloadLogEntity>> = db.logs().observeDownloads(limit)

    fun downloadLog(episodeId: Long, limit: Int): Flow<List<DownloadLogEntity>> = db.logs().observeDownloadsFor(episodeId, limit)

    /** SPEC §10: one row per position change. [PositionChange.at] is ignored; the row gets this clock's time. */
    suspend fun addPosition(change: PositionChange) {
        db.withTransaction {
            db.logs().insertPosition(
                PositionLogEntity(0, change.episodeId, clock(), change.fromMs, change.toMs, change.cause, change.streaming),
            )
            db.logs().trimPositions(KEEP_POSITION_ROWS)
        }
    }

    fun positionLog(limit: Int): Flow<List<PositionChange>> = db.logs().observePositions(limit).map { rows ->
        rows.map { PositionChange(it.id, it.episodeId, it.at, it.fromMs, it.toMs, it.cause, it.streaming) }
    }

    private fun clean(message: String?): String? = message?.let { scrubUrls(it).take(MAX_MESSAGE) }

    companion object {
        const val MAX_MESSAGE = 2_000
        const val KEEP_ROWS = 1_000

        /** Several long listening sessions' worth of seeks, skips and transitions. */
        const val KEEP_POSITION_ROWS = 2_000
    }
}
