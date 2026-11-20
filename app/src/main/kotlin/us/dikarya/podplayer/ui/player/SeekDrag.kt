/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A seek-bar drag, bound to the episode it started on. While the finger moves, the bar shows the finger's value;
 * the seek is sent once, on release. If the session moves to another episode mid-drag (the one playing ended),
 * the drag is dropped for good: the bar goes back to the episode's position, later moves of the same gesture
 * change nothing, and the release seeks nothing, so the first episode's spot is never applied to another one.
 * Gesture state only; playback state is the session's. Callers report each episode they show with [onEpisode].
 */
class SeekDrag {
    private var value by mutableStateOf<Float?>(null)
    private var episodeId: Long? = null
    private var dropped by mutableStateOf(false)

    /** The session's episode is [episode] now. A drag that started on another one is dropped. */
    fun onEpisode(episode: Long?) {
        if (value != null && episode != episodeId) dropped = true
    }

    /** The finger moved to [to] on [episode]'s bar. */
    fun move(to: Float, episode: Long?) {
        onEpisode(episode)
        if (dropped) return
        if (value == null) episodeId = episode
        value = to
    }

    /** What the bar shows instead of the position, or null to show the position. */
    fun shown(episode: Long?): Float? = value?.takeIf { !dropped && episode == episodeId }

    /** The finger lifted: the value to seek to, or null when there is nothing to seek (no drag, or it was dropped). */
    fun release(episode: Long?): Float? {
        val seek = shown(episode)
        value = null
        episodeId = null
        dropped = false
        return seek
    }
}
