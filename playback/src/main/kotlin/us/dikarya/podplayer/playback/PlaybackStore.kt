/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.Flow
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.core.model.PositionChange

/** Everything the player needs to know about one episode. */
data class PlayableEpisode(
    val id: Long,
    val feedId: Long,
    val title: String?,
    val showTitle: String?,
    /** The feed's enclosure URL. Feed-supplied, so it goes through the URL policy and the network gate. */
    val streamUrl: String,
    /** The finished download, if it exists on disk right now; playback prefers it over the stream. */
    val localFile: File?,
    /** The feed's owner-approved host (SPEC §3), so a stream on the owner's own server is allowed. */
    val approvedHost: String?,
    /** A content URI of a sandbox-produced cache file (SPEC §6), never a remote URL. */
    val artworkUri: Uri?,
    val durationMs: Long?,
) {
    override fun toString() = "PlayableEpisode($id, feed=$feedId, file=${localFile != null})"
}

/**
 * What the playback engine reads and writes. The database is the source of truth for the queue (CLAUDE.md); this
 * interface keeps the engine testable without Room.
 */
interface PlaybackStore {
    suspend fun episode(id: Long): PlayableEpisode?

    /** Where to start [id]: the saved position, or 0 when there is none or the episode was played. */
    suspend fun startPosition(id: Long): Long

    suspend fun savePosition(id: Long, positionMs: Long, durationMs: Long?)

    suspend fun markPlayed(id: Long)

    /** The queue's episode ids, in order. */
    fun queue(): Flow<List<Long>>

    suspend fun putAtQueueHead(id: Long)

    suspend fun removeFromQueue(id: Long)

    /** The episode loaded in the player on this phone, kept for playback resumption. */
    suspend fun currentEpisodeId(): Long?

    suspend fun setCurrentEpisodeId(id: Long?)

    fun settings(): Flow<PlaybackSettings>

    suspend fun showSpeed(feedId: Long): Float?

    suspend fun setShowSpeed(feedId: Long, speed: Float?)

    suspend fun setGlobalSpeed(speed: Float)

    /** Ids of episodes whose download is complete. */
    fun downloaded(): Flow<Set<Long>>

    suspend fun logPosition(change: PositionChange)

    suspend fun appendAction(id: Long, kind: EpisodeActionKind, positionMs: Long?, durationMs: Long?)

    /** A line for the app log. Callers never put URLs here unredacted. */
    fun log(message: String)
}
