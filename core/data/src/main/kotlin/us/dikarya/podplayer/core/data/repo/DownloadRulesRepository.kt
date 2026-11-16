/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.model.Episode

/** A finished download, with what the clean-up rules (SPEC §9) decide on. */
data class CleanUpCandidate(
    val episodeId: Long,
    /** When the download finished. */
    val completedAt: Long?,
    val played: Boolean,
    /** When it was marked played (the playback record's last change), or null when it isn't played. */
    val playedAt: Long?,
    val starred: Boolean,
    val queued: Boolean,
)

/**
 * What `:core:jobs` reads for auto-download and clean-up (SPEC §9). The rules about time and settings live in
 * the jobs layer; this only answers questions about the library.
 */
class DownloadRulesRepository internal constructor(private val db: PodDatabase, private val clock: () -> Long) {
    /**
     * Episodes auto-download should fetch, oldest first: their show has it on, they were published after it was
     * turned on, they are still in the feed, have no download record (finished, waiting, failed or deleted by the
     * owner after one), aren't played, and auto-download never queued them before.
     */
    suspend fun autoDownloadCandidates(limit: Int): List<Episode> = db.downloadRules().autoDownloadCandidates(limit).map { it.toModel() }

    /** Starred episodes with no download record that auto-download never queued ("keep starred downloaded"). */
    suspend fun starredNotDownloaded(limit: Int): List<Episode> = db.downloadRules().starredNotDownloaded(limit).map { it.toModel() }

    /** Auto-download queued [episodeId]: it is never queued automatically again. */
    suspend fun markAutoQueued(episodeId: Long) {
        db.downloadRules().markAutoQueued(episodeId, clock())
    }

    /** Every finished download. */
    suspend fun cleanUpCandidates(): List<CleanUpCandidate> = db.downloadRules().cleanUpCandidates(null)

    /** [episodeId]'s finished download as it is now, or null when it isn't a finished download (any more). */
    suspend fun cleanUpCandidate(episodeId: Long): CleanUpCandidate? = db.downloadRules().cleanUpCandidates(episodeId).firstOrNull()

    /** Changes whenever an episode is marked played or unplayed: clean-up runs soon after (SPEC §9). */
    fun playedCount(): Flow<Int> = db.downloadRules().observePlayedCount().distinctUntilChanged()

    fun starredCount(): Flow<Int> = db.downloadRules().observeStarredCount().distinctUntilChanged()
}
