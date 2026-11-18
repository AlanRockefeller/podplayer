/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

/**
 * Notices playing without sound (owner's phone, 2026-11-17: a stream cut short played on in silence, the position
 * advancing to the end with nothing going out). Fed the position and the audio renderer's count of buffers handed
 * to the output while playing; when the position moves on by [limitMs] with no buffer going out, [check] returns
 * the position where the sound stopped, once. Call [reset] on every seek, start and change of episode.
 */
internal class SilenceWatch(private val limitMs: Long = SILENCE_LIMIT_MS) {
    private var lastCount: Int? = null
    private var soundAtMs = 0L

    fun check(positionMs: Long, renderedBuffers: Int?): Long? {
        if (renderedBuffers == null) {
            reset()
            return null
        }
        if (renderedBuffers != lastCount || positionMs < soundAtMs) {
            lastCount = renderedBuffers
            soundAtMs = positionMs
            return null
        }
        if (positionMs - soundAtMs < limitMs) return null
        val stoppedAt = soundAtMs
        reset()
        return stoppedAt
    }

    fun reset() {
        lastCount = null
    }

    companion object {
        /** Ordinary playback hands audio out every few milliseconds; this much position without any is silence. */
        const val SILENCE_LIMIT_MS = 15_000L
    }
}
