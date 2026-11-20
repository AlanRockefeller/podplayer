/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has a network that reaches the internet, for the screens' offline messages (M1-S8). It only
 * watches; it never connects to anything. Downloads and refreshes have their own WorkManager constraints.
 */
class NetworkStatus(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = mutable.asStateFlow()

    init {
        connectivity?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                /** The network the last capabilities were for. */
                private var current: Network? = null

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    current = network
                    mutable.value = reachesInternet(capabilities)
                }

                // A read here can still return the lost network (Android's NetworkCallback docs); a replacement
                // network reports itself through onCapabilitiesChanged (CodeRabbit review, PR #1). A late loss of
                // a network already replaced changes nothing.
                override fun onLost(network: Network) {
                    if (current != null && network != current) return
                    current = null
                    mutable.value = false
                }
            },
        )
    }

    private fun isOnlineNow(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return reachesInternet(capabilities)
    }

    internal companion object {
        /**
         * Internet, and not held at a captive portal's sign-in page. Not NET_CAPABILITY_VALIDATED: some working
         * networks never pass Android's check (a blocked check server, a VPN, the moment after connecting), and
         * "offline" here stops Discover from searching (CodeRabbit review, PR #1).
         */
        fun reachesInternet(capabilities: NetworkCapabilities): Boolean =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }
}
