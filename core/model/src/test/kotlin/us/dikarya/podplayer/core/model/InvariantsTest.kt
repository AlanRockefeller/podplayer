/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InvariantsTest {
    @After
    fun restoreDefault() {
        Invariants.handler = StrictInvariantHandler
    }

    @Test
    fun defaultHandlerIsStrict() {
        assertEquals(StrictInvariantHandler, Invariants.handler)
    }

    @Test
    fun strictHandlerThrowsOnViolation() {
        Invariants.handler = StrictInvariantHandler
        try {
            assertInvariant(false) { "queue position out of range" }
            fail("expected a crash")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("queue position out of range"))
        }
    }

    @Test
    fun loggingHandlerRecordsAndContinues() {
        val logged = mutableListOf<String>()
        Invariants.handler = LoggingInvariantHandler { logged += it }
        assertInvariant(false) { "queue position out of range" }
        assertEquals(listOf("Invariant violated: queue position out of range"), logged)
    }

    @Test
    fun holdingInvariantDoesNotBuildTheMessage() {
        var built = false
        assertInvariant(true) {
            built = true
            "unused"
        }
        assertFalse(built)
    }
}
