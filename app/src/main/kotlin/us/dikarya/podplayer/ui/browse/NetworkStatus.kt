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
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    mutable.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    mutable.value = isOnlineNow()
                }
            },
        )
    }

    private fun isOnlineNow(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
