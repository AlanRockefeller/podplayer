/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The offline banner, and Discover's "you're offline", follow the default network (M1-S8). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NetworkStatusTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val network = ShadowNetwork.newInstance(7)

    private fun capabilities(vararg capability: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps -> capability.forEach { shadowOf(caps).addCapability(it) } }

    private fun watch(): Pair<NetworkStatus, ConnectivityManager.NetworkCallback> {
        val status = NetworkStatus(context)
        val callback = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.single()
        return status to callback
    }

    /** CodeRabbit review, PR #1: the lost network can still be read back as connected inside onLost. */
    @Test
    fun losingTheDefaultNetworkIsOfflineAtOnce() {
        val (status, callback) = watch()
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(status.online.value)
        // The stale read Android warns about: the lost network still looks connected.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        callback.onLost(network)
        assertFalse(status.online.value)
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(8), capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue("a replacement network is online", status.online.value)
        callback.onLost(network)
        assertTrue("a late loss of the network it replaced changes nothing", status.online.value)
    }

    /** CodeRabbit review, PR #1: a captive portal's network claims internet before the owner signs in. */
    @Test
    fun aCaptivePortalIsOfflineButAnUnvalidatedNetworkIsNot() {
        val (status, callback) = watch()
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        assertFalse(status.online.value)
        // Not validated (a blocked check server, a VPN, just connected): still online, so Discover can search.
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(status.online.value)
        callback.onCapabilitiesChanged(network, capabilities())
        assertFalse(status.online.value)
    }
}
