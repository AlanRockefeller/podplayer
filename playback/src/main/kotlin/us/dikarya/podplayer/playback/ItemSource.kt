/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.redact

/**
 * Where an item's audio comes from, attached as the [MediaItem] tag. Tags never leave this process (Media3 drops
 * the local configuration when it sends items to controllers), so feed URLs with tokens stay here. Only items
 * built by [toMediaItem] reach the player: a URI a controller supplies is never played.
 */
data class ItemSource(
    val episodeId: Long,
    /** Play this file; null means stream [streamUrl]. */
    val file: File?,
    val streamUrl: String,
    val approvedHost: String?,
) {
    val streaming: Boolean get() = file == null

    override fun toString() = "ItemSource($episodeId, ${if (file != null) "file" else redact(streamUrl)})"

    companion object {
        fun of(item: MediaItem?): ItemSource? = item?.localConfiguration?.tag as? ItemSource
    }
}

/** The player item for this episode, from its file when [useFile] and a file exists, else from the stream. */
internal fun PlayableEpisode.toMediaItem(useFile: Boolean): MediaItem {
    val source = ItemSource(id, localFile.takeIf { useFile }, streamUrl, approvedHost)
    // The same URI GatedMediaSourceFactory gives the source (the policy's canonical form), so an item with new
    // metadata (artwork fetched later) updates its source in place instead of restarting the audio.
    val streamUri = (UrlPolicy.checkFeedUrl(streamUrl) as? UrlCheck.Allowed)?.url?.toString() ?: streamUrl
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(source.file?.let(Uri::fromFile) ?: Uri.parse(streamUri))
        .setTag(source)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(showTitle)
                .setAlbumTitle(showTitle)
                .setArtworkUri(artworkUri)
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build(),
        )
        .build()
}
