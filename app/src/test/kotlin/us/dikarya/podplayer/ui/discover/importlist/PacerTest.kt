/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover.importlist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Apple's search is paced: one request per interval, however fast the caller asks. */
class PacerTest {
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()
    private val pacer = Pacer(intervalMs = 3_000, now = { now }) { ms ->
        sleeps += ms
        now += ms
    }

    @Test
    fun theFirstRequestGoesAtOnceAndTheRestWait() = runBlocking {
        pacer.await()
        pacer.await()
        pacer.await()
        assertEquals(listOf(3_000L, 3_000L), sleeps)
    }

    @Test
    fun timeAlreadySpentCounts() = runBlocking {
        pacer.await()
        now += 2_000
        pacer.await()
        now += 5_000
        pacer.await()
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun slowingDownDoublesTheIntervalUpToACap() = runBlocking {
        pacer.await()
        pacer.slowDown()
        pacer.await()
        repeat(10) { pacer.slowDown() }
        pacer.await()
        assertEquals(listOf(6_000L, Pacer.MAX_INTERVAL_MS), sleeps)
    }
}
