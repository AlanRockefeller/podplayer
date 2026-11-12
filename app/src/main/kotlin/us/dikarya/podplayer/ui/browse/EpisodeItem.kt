/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.model.PlaybackRecord
import us.dikarya.podplayer.playback.PlaybackUiState

/** One episode as a list row or the episode page shows it. Playback fields come from the session only. */
data class EpisodeItem(
    val episode: Episode,
    val showTitle: String?,
    /** The episode's own image, else the show's (both feed-supplied; shown only through the sandbox). */
    val imageUrl: String?,
    val approvedHost: String?,
    val record: PlaybackRecord?,
    val download: MediaFile?,
    val queued: Boolean,
    /** The session's current episode. */
    val loaded: Boolean,
    /** Loaded and set to play (playing or about to). */
    val playing: Boolean,
    /** The session's live position, while [loaded]. */
    val livePositionMs: Long? = null,
) {
    val id: Long get() = episode.id
    val played: Boolean get() = record?.played == true
    val durationMs: Long? get() = download?.probedDurationMs ?: episode.durationMs ?: record?.durationMs
    val positionMs: Long get() = livePositionMs ?: record?.positionMs ?: 0
    val downloadState: DownloadState? get() = download?.state
    val downloaded: Boolean get() = download?.state == DownloadState.Complete

    /** 0..1 through the episode, or null when it hasn't started, is played, or has no known length. */
    val progress: Float?
        get() {
            val duration = durationMs ?: return null
            if (played || duration <= 0 || positionMs <= 0) return null
            return (positionMs.toFloat() / duration).coerceIn(0f, 1f)
        }

    companion object {
        fun of(
            episode: Episode,
            feed: Feed?,
            record: PlaybackRecord?,
            download: MediaFile?,
            queued: Boolean,
            playback: PlaybackUiState,
        ): EpisodeItem {
            val loaded = playback.episodeId == episode.id
            return EpisodeItem(
                episode = episode,
                showTitle = feed?.title,
                imageUrl = episode.imageUrl ?: feed?.imageUrl,
                approvedHost = feed?.ownerApprovedHost,
                record = record,
                download = download,
                queued = queued,
                loaded = loaded,
                playing = loaded && playback.playWhenReady,
                livePositionMs = if (loaded) playback.positionMs else null,
            )
        }
    }
}

/** What every episode row can do. Playback goes to the session as commands; the rest goes to the library. */
class EpisodeActions(private val deps: BrowseDeps, private val scope: CoroutineScope) {
    /** Pauses the playing episode; anything else plays (from its saved position, at the top of the queue). */
    fun togglePlay(item: EpisodeItem) {
        if (item.playing) deps.playback.pause() else deps.playback.playEpisode(item.id)
    }

    /** A timestamp in the notes: this episode, from that spot. */
    fun playAt(episodeId: Long, positionMs: Long) = deps.playback.playEpisode(episodeId, positionMs)

    fun toggleQueue(item: EpisodeItem) {
        scope.launch {
            val queue = deps.repositories()?.queue ?: return@launch
            if (item.queued) queue.remove(item.id) else queue.add(item.id)
        }
    }

    fun download(episodeId: Long) {
        scope.launch { deps.downloads.download(episodeId) }
    }

    fun cancelDownload(episodeId: Long) {
        scope.launch { deps.downloads.cancel(episodeId) }
    }

    fun deleteDownload(episodeId: Long) {
        scope.launch { deps.downloads.delete(episodeId) }
    }

    fun setPlayed(episodeId: Long, played: Boolean) {
        scope.launch { deps.repositories()?.playback?.setPlayed(episodeId, played) }
    }
}
