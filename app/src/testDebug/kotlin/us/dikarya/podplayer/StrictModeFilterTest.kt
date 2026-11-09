/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictModeFilterTest {
    private fun frame(className: String) = StackTraceElement(className, "run", "X.kt", 1)

    @Test
    fun violationInsideOurCodeCrashes() {
        val trace = arrayOf(
            frame("android.os.StrictMode\$AndroidBlockGuardPolicy"),
            frame("java.io.File"),
            frame("androidx.datastore.core.SingleProcessDataStore"),
            frame("us.dikarya.podplayer.MainActivity"),
            frame("android.app.ActivityThread"),
        )
        assertTrue(StrictModeFilter.shouldCrash(trace))
    }

    @Test
    fun vendorFrameworkViolationOnlyLogs() {
        // Samsung's IdsController reads SharedPreferences on the main thread on every resume (M1-S1).
        val trace = arrayOf(
            frame("android.os.StrictMode\$AndroidBlockGuardPolicy"),
            frame("java.io.File"),
            frame("android.app.ContextImpl"),
            frame("android.app.IdsController"),
            frame("android.app.ActivityThread"),
            frame("com.android.internal.os.ZygoteInit"),
        )
        assertFalse(StrictModeFilter.shouldCrash(trace))
    }

    @Test
    fun lookAlikePackagesDoNotCount() {
        assertFalse(StrictModeFilter.shouldCrash(arrayOf(frame("us.dikarya.podplayerx.Other"))))
        assertFalse(StrictModeFilter.shouldCrash(arrayOf(frame("us.dikarya.daydream.Main"))))
    }

    @Test
    fun emptyTraceOnlyLogs() {
        assertFalse(StrictModeFilter.shouldCrash(emptyArray()))
    }
}
