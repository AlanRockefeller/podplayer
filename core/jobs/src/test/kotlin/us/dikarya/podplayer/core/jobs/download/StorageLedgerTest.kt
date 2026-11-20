/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storage cap's bookkeeping while downloads start and end around each other (PR #14 review). */
class StorageLedgerTest {
    private val cap = 100L

    @Test
    fun aDownloadThatEndsStaysCountedUntilItsRecordTakesOver() = runBlocking {
        val ledger = StorageLedger()
        val a = ledger.enter({ emptyMap() }, 1, 0)
        assertTrue(ledger.take(a, 60, cap))
        val b = ledger.enter({ emptyMap() }, 2, 0)
        // A ends; its record (60 bytes kept, Paused) is read slowly.
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val leaving = launch(Dispatchers.Default) {
            ledger.leave({ reading.complete(Unit); release.await(); mapOf(1L to 60L) }, 1)
        }
        reading.await()
        assertFalse("A's 60 bytes still count while its record is read", ledger.take(b, 50, cap))
        release.complete(Unit)
        leaving.join()
        assertFalse("and after, from its record", ledger.take(b, 50, cap))
        assertTrue(ledger.take(b, 40, cap))
    }

    @Test
    fun aStartCancelledWhileReadingTheRecordsLeavesNothingCounted() = runBlocking {
        val ledger = StorageLedger()
        val reading = CompletableDeferred<Unit>()
        val start = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            ledger.enter({ reading.complete(Unit); CompletableDeferred<Unit>().await(); emptyMap() }, 1, 80)
        }
        reading.await()
        start.cancel()
        start.join()
        yield()
        val other = ledger.enter({ emptyMap() }, 2, 0)
        assertTrue("the cancelled start's 80 bytes aren't held", ledger.take(other, 90, cap))
        assertTrue(other.get() == 90L)
    }
}
