/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.model.Invariants
import us.dikarya.podplayer.core.model.StrictInvariantHandler

/**
 * CLAUDE.md: bad input never crashes the app. A release build records a violated invariant and keeps going,
 * from the first line of code that runs in the process.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class) // so Robolectric doesn't start the real app around the test
class ReleaseInvariantHandlerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aViolatedInvariantIsLoggedAndTheAppKeepsGoing() {
        val log = AppLog(dir = { temp.root }, echo = false)
        BuildTypeSetup.invariantHandler { log }.onViolation("queue position out of range")
        log.flush()
        val text = log.files().joinToString("") { it.readText() }
        assertTrue(text, "Invariant violated: queue position out of range" in text)
    }

    @Test
    fun aViolationBeforeTheLogExistsDoesNotThrow() {
        BuildTypeSetup.invariantHandler { null }.onViolation("too early for the log")
    }

    @Test
    fun theApplicationInstallsTheHandlerBeforeOnCreate() {
        // ContentProviders run before Application.onCreate; the constructor is the earliest hook there is.
        // Process-wide: put back what was there, so later tests don't inherit the app's (CodeRabbit review, PR 2).
        val before = Invariants.handler
        try {
            Invariants.handler = StrictInvariantHandler
            PodplayerApp()
            assertFalse(Invariants.handler is StrictInvariantHandler)
            Invariants.handler.onViolation("from a content provider")
        } finally {
            Invariants.handler = before
        }
    }
}
