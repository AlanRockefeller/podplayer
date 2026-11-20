/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The seek bar sends one seek on release, and only to the episode the drag started on. */
class SeekDragTest {
    @Test
    fun aDragShowsTheFingerAndSeeksOnceOnRelease() {
        val drag = SeekDrag()
        assertNull(drag.shown(A))
        drag.move(0.3f, A)
        drag.move(0.5f, A)
        assertEquals(0.5f, drag.shown(A)!!, 0f)
        assertEquals(0.5f, drag.release(A)!!, 0f)
        assertNull("released", drag.shown(A))
        assertNull("nothing more to seek", drag.release(A))
    }

    @Test
    fun aDragAcrossAnEpisodeChangeSeeksNothing() {
        // Episode A ended while the owner held the slider at half way; B started. Half of B must not be skipped.
        val drag = SeekDrag()
        drag.move(0.5f, A)
        assertNull("the bar shows B's own position", drag.shown(B))
        drag.move(0.6f, B)
        assertNull(drag.shown(B))
        assertNull(drag.release(B))
    }

    @Test
    fun aDragThatSawAnotherEpisodeStaysDroppedEvenIfTheFirstComesBack() {
        val drag = SeekDrag()
        drag.move(0.6f, A)
        drag.onEpisode(B)
        drag.onEpisode(A)
        assertNull(drag.shown(A))
        drag.move(0.7f, A)
        assertNull("later moves from the same gesture don't revive it", drag.shown(A))
        assertNull(drag.release(A))
    }

    @Test
    fun aMoveReportedOnAnotherEpisodeAlsoDropsTheDrag() {
        val drag = SeekDrag()
        drag.move(0.6f, A)
        drag.move(0.65f, B)
        assertNull(drag.release(A))
    }

    @Test
    fun theNextDragOnTheNewEpisodeWorks() {
        val drag = SeekDrag()
        drag.move(0.5f, A)
        drag.release(B)
        drag.move(0.2f, B)
        assertEquals(0.2f, drag.release(B)!!, 0f)
    }

    private companion object {
        const val A = 1L
        const val B = 2L
    }
}
