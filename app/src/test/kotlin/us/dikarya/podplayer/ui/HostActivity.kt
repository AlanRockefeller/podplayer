/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import us.dikarya.podplayer.ui.browse.eventually

/**
 * Lets the Compose test rule start its empty host activity. The usual way is the ui-test-manifest library, which
 * would put an exported activity into the debug build the owner uses every day; this registers it with
 * Robolectric for the test only.
 */
class HostActivity : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    name = ComponentActivity::class.java.name
                    packageName = app.packageName
                },
            )
            base.evaluate()
        }
    }
}

/**
 * Runs [close] after the rules inside it, so put it outside the Compose rule: the screen is gone before the library
 * it reads is closed (a screen still collecting would open new database flows on a closed database).
 */
class CloseAfter(private val close: () -> Unit) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                close()
            }
        }
    }
}

/**
 * Compose's `waitUntil` counts its own virtual time under Robolectric, which runs out at once while Room works on
 * real threads. This waits in real time, letting the main looper and Compose catch up between checks.
 */
fun ComposeContentTestRule.waitInRealTime(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    eventually(timeoutMs) {
        waitForIdle()
        condition()
    }
}
