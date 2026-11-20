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
 * the drag is dropped: the bar goes back to the new episode's position and the release seeks nothing, so the
 * first episode's spot is never applied to the next one. Gesture state only; playback state is the session's.
 */
class SeekDrag {
    private var value by mutableStateOf<Float?>(null)
    private var episodeId: Long? = null

    /** The finger moved to [to] on [episode]'s bar. */
    fun move(to: Float, episode: Long?) {
        if (value == null) episodeId = episode
        value = to
    }

    /** What the bar shows instead of the position, or null to show the position. */
    fun shown(episode: Long?): Float? = value?.takeIf { episode == episodeId }

    /** The finger lifted: the value to seek to, or null when there is nothing to seek (no drag, or it went stale). */
    fun release(episode: Long?): Float? {
        val seek = shown(episode)
        value = null
        episodeId = null
        return seek
    }
}
