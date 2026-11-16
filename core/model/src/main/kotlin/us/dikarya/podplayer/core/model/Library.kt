/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.model

/*
 * Domain types shared by the data layer, playback and the UI.
 *
 * URLs in here are secrets (private feeds carry tokens), and this module can't see `redact()`
 * from :core:net, so every toString() leaves URLs out entirely.
 */

/** A subscription. */
data class Feed(
    val id: Long,
    /** The current feed URL (updated by permanent redirects). Never log it unredacted. */
    val url: String,
    val title: String?,
    /** The show's website. */
    val link: String?,
    /** Raw HTML or text from the feed; render only through the show-notes sanitizer. */
    val description: String?,
    val author: String?,
    val imageUrl: String?,
    val categories: List<String>,
    /** The host the owner typed, which may be on a private network (SPEC §3), or null. */
    val ownerApprovedHost: String?,
    val subscribedAt: Long,
    val lastRefreshSuccessAt: Long?,
    val lastRefreshErrorAt: Long?,
    /** Plain language, already redacted. */
    val lastRefreshError: String?,
    /** While the feed is backing off after errors: the earliest automatic retry. Null: with the next refresh. */
    val nextRefreshAt: Long? = null,
) {
    /** SPEC §3: cleartext feeds are allowed and the UI marks them "not secure". */
    val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)

    override fun toString() = "Feed(id=$id, title=$title, ownerApproved=${ownerApprovedHost != null})"
}

/** The audio file the feed points at. [type] and [length] are hints only (SPEC §9). */
data class Enclosure(val url: String, val type: String?, val length: Long?) {
    override fun toString() = "Enclosure(type=$type, length=$length)"
}

data class Episode(
    val id: Long,
    val feedId: Long,
    val guid: String?,
    val title: String?,
    /** The feed's date, or the first-seen time when the feed had none (SPEC §4). */
    val publishedAt: Long,
    val enclosure: Enclosure,
    /** From the feed; the probed duration of a download lives with the media file. */
    val durationMs: Long?,
    /** Raw HTML; render only through the show-notes sanitizer. */
    val notes: String?,
    val imageUrl: String?,
    val link: String?,
    val season: Int?,
    val number: Int?,
    val chaptersUrl: String?,
    /** False once the feed stops listing it. It stays in the library (SPEC §4). */
    val inFeed: Boolean,
    /** When the owner starred it (SPEC §12), or null when it isn't starred. */
    val starredAt: Long? = null,
) {
    val starred: Boolean get() = starredAt != null

    override fun toString() = "Episode(id=$id, feedId=$feedId, title=$title, inFeed=$inFeed, starred=$starred)"
}

/** One entry of Up Next. The database queue is the source of truth; the player's playlist projects it. */
data class QueueEntry(val episodeId: Long, val position: Int, val addedAt: Long)

/** Where the owner is in an episode. */
data class PlaybackRecord(
    val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long?,
    val played: Boolean,
    val updatedAt: Long,
) {
    companion object {
        /** Owner, 2026-11-15 (SPEC §10): reaching 98% of an episode counts as played. */
        const val PLAYED_FRACTION = 0.98

        /** True from [PLAYED_FRACTION] of a known, positive [durationMs] on. */
        fun countsAsPlayed(positionMs: Long, durationMs: Long?): Boolean =
            durationMs != null && durationMs > 0 && positionMs >= durationMs * PLAYED_FRACTION
    }
}

/**
 * Kinds of [EpisodeAction], the append-only history M2 sync sends to the owner's server. Stored by name: add new
 * kinds at will, but renaming one needs a migration.
 */
enum class EpisodeActionKind { Play, Pause, Seek, Played, Downloaded, Deleted, Starred, Unstarred }

data class EpisodeAction(
    val id: Long,
    /** Null once the episode was deleted from the library; the action stays for sync. */
    val episodeId: Long?,
    val kind: EpisodeActionKind,
    val positionMs: Long?,
    val durationMs: Long?,
    val at: Long,
    val deviceId: String,
    val synced: Boolean,
)

enum class DownloadState {
    /** Waiting for its turn or for a network that matches the settings. */
    Queued,
    Downloading,

    /** Stopped part-way; the bytes on disk can be resumed. */
    Paused,
    Failed,

    /** Transfer ended cleanly and Media3 read its duration (SPEC §9). */
    Complete,
}

/** A downloaded (or downloading) file. [relativePath] is relative to the app's files folder (SPEC §7). */
data class MediaFile(
    val episodeId: Long,
    val relativePath: String?,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val probedDurationMs: Long?,
    val completedAt: Long?,
)
