/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.discover.importlist.ListImportScreen
import us.dikarya.podplayer.ui.discover.importlist.ListImportViewModel
import us.dikarya.podplayer.ui.discover.success
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** Import a list: matching a long list must not stop when the screen would turn off, so the screen stays on. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ListImportUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(HostActivity()).around(compose)

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    @Test
    fun theScreenStaysOnWhileMatchingRunsAndNotAfter() {
        val held = CompletableDeferred<Unit>()
        env.beforeAnswer = { held.await() }
        env.answer = { success("""{"results":[{"collectionName":"Gardening","feedUrl":"https://g.example.com/f.xml"}]}""".toByteArray(), "application/json") }
        val vm = ListImportViewModel(env.deps)
        var view: View? = null
        var shown by mutableStateOf(true)
        compose.setContent {
            view = LocalView.current
            PodplayerTheme(DarkTokens) {
                if (shown) ListImportScreen(vm, onOpenShow = {}, onDone = {})
            }
        }
        vm.onTextChange("Gardening")
        vm.findShows()
        compose.waitInRealTime { vm.state.value.matching }
        compose.waitInRealTime { view!!.keepScreenOn }

        held.complete(Unit)
        compose.waitInRealTime { !vm.state.value.matching }
        compose.waitInRealTime { !view!!.keepScreenOn }
        assertFalse(view!!.keepScreenOn)

        // Leaving the screen mid-match clears the flag too.
        val again = CompletableDeferred<Unit>()
        env.beforeAnswer = { again.await() }
        vm.startOver()
        vm.findShows()
        compose.waitInRealTime { view!!.keepScreenOn }
        shown = false
        compose.waitInRealTime { !view!!.keepScreenOn }
        assertTrue(vm.state.value.matching)
        again.complete(Unit)
    }
}
