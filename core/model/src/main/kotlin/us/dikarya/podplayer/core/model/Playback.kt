/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.model

/**
 * The owner's playback settings (SPEC §10). Every value is clamped by [normalized], because a restored backup
 * can hold anything.
 */
data class PlaybackSettings(
    /** The global speed; a show's own speed (feed_settings) wins over it. Pitch is always kept. */
    val speed: Float = 1.0f,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    /** "Rewind N seconds after a pause longer than M minutes"; off by default (SPEC §10). */
    val rewindAfterPause: Boolean = false,
    val rewindSeconds: Int = 10,
    val rewindAfterPauseMinutes: Int = 10,
) {
    fun normalized(): PlaybackSettings = copy(
        speed = clampSpeed(speed),
        skipBackSeconds = skipBackSeconds.coerceIn(MIN_SKIP_SECONDS, MAX_SKIP_SECONDS),
        skipForwardSeconds = skipForwardSeconds.coerceIn(MIN_SKIP_SECONDS, MAX_SKIP_SECONDS),
        rewindSeconds = rewindSeconds.coerceIn(MIN_REWIND_SECONDS, MAX_REWIND_SECONDS),
        rewindAfterPauseMinutes = rewindAfterPauseMinutes.coerceIn(MIN_REWIND_AFTER_PAUSE_MINUTES, MAX_REWIND_AFTER_PAUSE_MINUTES),
    )

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val MIN_SKIP_SECONDS = 5
        const val MAX_SKIP_SECONDS = 300
        const val MIN_REWIND_SECONDS = 1
        const val MAX_REWIND_SECONDS = 120
        const val MIN_REWIND_AFTER_PAUSE_MINUTES = 1
        const val MAX_REWIND_AFTER_PAUSE_MINUTES = 24 * 60

        /** NaN and infinities become 1.0; anything else is held to [MIN_SPEED]..[MAX_SPEED]. */
        fun clampSpeed(speed: Float): Float = if (speed.isFinite()) speed.coerceIn(MIN_SPEED, MAX_SPEED) else 1.0f
    }
}

/** Why the playback position moved (SPEC §10: "every position change is logged with its cause"). */
enum class PositionCause {
    /** A seek whose sender we know nothing more about: a seek bar, the lock screen, a car. */
    Seek,
    SkipBack,
    SkipForward,

    /** The saved position applied when an episode is loaded. */
    Restore,

    /** The optional "rewind after a long pause" setting. */
    RewindAfterPause,

    /** The previous episode ended and this one started. */
    NextEpisode,

    /** The same episode moved from the stream to its downloaded file. */
    SwitchToFile,

    /** The downloaded file failed, so the episode went back to the stream. */
    FallBackToStream,

    /** The player moved the position itself (seek adjustment, internal discontinuity). */
    Player,

    /** The position moved without any event explaining it: the jumps LESSONS "Position jumps" is about. */
    Unexplained,

    /** It was playing without sound, so the episode was loaded again from where the sound stopped. */
    SilentRecovery,
}

/** One row of the position log. [episodeId] has no foreign key: the log outlives an unsubscribe. */
data class PositionChange(
    val id: Long = 0,
    val episodeId: Long,
    val at: Long,
    val fromMs: Long,
    val toMs: Long,
    val cause: PositionCause,
    val streaming: Boolean,
)
