/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.core.model.PositionChange
import us.dikarya.podplayer.core.net.scrubUrls

/**
 * The [PlaybackStore] over the app's database and settings. When the library couldn't be opened (damaged, see
 * DataLayer), it behaves as an empty library: nothing plays, nothing crashes.
 *
 * [artwork] returns a content URI of a sandbox-produced cache file, or null (SPEC §6); it never fetches.
 * [fetchArtwork] fetches through the gate and the sandbox first (with the feed's owner-approved host), then does
 * the same.
 */
class RoomPlaybackStore(
    private val data: DataLayer,
    private val log: AppLog,
    private val fetchArtwork: suspend (url: String?, approvedHost: String?) -> Uri? = { _, _ -> null },
    private val artwork: suspend (url: String?) -> Uri?,
) : PlaybackStore {
    private suspend fun repos(): Repositories? = data.awaitRepositories()

    override suspend fun fetchArtwork(id: Long): Uri? {
        val repos = repos() ?: return null
        val episode = repos.episodes.get(id) ?: return null
        val feed = repos.feeds.get(episode.feedId)
        return fetchArtwork(episode.imageUrl ?: feed?.imageUrl, feed?.ownerApprovedHost)
    }

    override suspend fun episode(id: Long): PlayableEpisode? {
        val repos = repos() ?: return null
        val episode = repos.episodes.get(id) ?: return null
        val feed = repos.feeds.get(episode.feedId)
        val media = repos.media.get(id)
        val file = if (media?.state == DownloadState.Complete) {
            withContext(Dispatchers.IO) { data.paths.resolve(media.relativePath)?.takeIf { it.isFile } }
        } else {
            null
        }
        return PlayableEpisode(
            id = id,
            feedId = episode.feedId,
            title = episode.title,
            showTitle = feed?.title,
            streamUrl = episode.enclosure.url,
            localFile = file,
            approvedHost = feed?.ownerApprovedHost,
            artworkUri = artwork(episode.imageUrl ?: feed?.imageUrl),
            durationMs = media?.probedDurationMs ?: episode.durationMs,
        )
    }

    override suspend fun startPosition(id: Long): Long {
        val record = repos()?.playback?.get(id) ?: return 0
        return if (record.played) 0 else record.positionMs
    }

    override suspend fun savePosition(id: Long, positionMs: Long, durationMs: Long?) {
        repos()?.playback?.savePosition(id, positionMs, durationMs)
    }

    override suspend fun markPlayed(id: Long) {
        repos()?.playback?.setPlayed(id, true)
    }

    override fun queue(): Flow<List<Long>> = flow {
        val repos = repos() ?: return@flow emit(emptyList())
        emitAll(repos.queue.entries().map { entries -> entries.map { it.episodeId } }.distinctUntilChanged())
    }

    override suspend fun putAtQueueHead(id: Long) {
        repos()?.queue?.add(id, index = 0)
    }

    override suspend fun removeFromQueue(id: Long) {
        repos()?.queue?.remove(id)
    }

    override suspend fun currentEpisodeId(): Long? = data.device.currentEpisodeId()

    override suspend fun setCurrentEpisodeId(id: Long?) = data.device.setCurrentEpisodeId(id)

    override fun settings(): Flow<PlaybackSettings> = data.settings.playback

    override suspend fun showSpeed(feedId: Long): Float? = repos()?.feedSettings?.speed(feedId)?.first()

    override suspend fun setShowSpeed(feedId: Long, speed: Float?) {
        repos()?.feedSettings?.setSpeed(feedId, speed)
    }

    override suspend fun setGlobalSpeed(speed: Float) = data.settings.setPlaybackSpeed(speed)

    override fun downloaded(): Flow<Set<Long>> = flow {
        val repos = repos() ?: return@flow emit(emptySet())
        emitAll(
            repos.media.observeAll()
                .map { files -> files.filter { it.state == DownloadState.Complete }.map { it.episodeId }.toSet() }
                .distinctUntilChanged(),
        )
    }

    override suspend fun logPosition(change: PositionChange) {
        repos()?.logs?.addPosition(change)
    }

    override suspend fun appendAction(id: Long, kind: EpisodeActionKind, positionMs: Long?, durationMs: Long?) {
        repos()?.actions?.append(id, kind, positionMs, durationMs)
    }

    override fun log(message: String) = log.i(TAG, scrubUrls(message))

    private companion object {
        const val TAG = "Playback"
    }
}
