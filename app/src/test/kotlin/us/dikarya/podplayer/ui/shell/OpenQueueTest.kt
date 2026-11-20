/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.shell

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.reflect.KClass
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.HostActivity

/** The player's "Open queue" shows the queue, and no tab brings the player back afterwards (SPEC §12). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class OpenQueueTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(HostActivity()).around(compose)

    private lateinit var nav: NavHostController

    private fun shell() = compose.setContent {
        val controller = rememberNavController()
        nav = controller
        NavHost(controller, startDestination = HomeRoute) {
            composable<HomeRoute> { Text("home") }
            composable<LibraryRoute> { Text("library") }
            composable<QueueRoute> { Text("queue") }
            composable<PlayerRoute> { Text("player") }
            composable<ShowRoute> { Text("show") }
        }
    }

    private fun act(block: NavHostController.() -> Unit) {
        compose.runOnIdle { nav.block() }
        compose.waitForIdle()
    }

    private fun assertOn(route: KClass<*>) {
        val destination = compose.runOnIdle { nav.currentDestination }
        assertTrue("on ${destination?.route}, expected ${route.simpleName}", destination?.hasRoute(route) == true)
    }

    @Test
    fun fromThePlayerOpenedOnTheQueueTab() {
        shell()
        act { navigateToTab(QueueRoute) }
        act { navigate(PlayerRoute) }
        act { openQueue() }
        assertOn(QueueRoute::class)
        act { navigateToTab(HomeRoute) }
        act { navigateToTab(QueueRoute) }
        assertOn(QueueRoute::class)
    }

    @Test
    fun fromThePlayerOpenedOnAnotherTab() {
        shell()
        act { navigateToTab(LibraryRoute) }
        act { navigate(ShowRoute(1)) }
        act { navigate(PlayerRoute) }
        act { openQueue() }
        assertOn(QueueRoute::class)
        act { navigateToTab(LibraryRoute) }
        assertOn(ShowRoute::class)
        act { navigateToTab(QueueRoute) }
        assertOn(QueueRoute::class)
    }
}
