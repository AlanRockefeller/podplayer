/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.app.Application
import android.content.Context
import us.dikarya.podplayer.core.model.Invariants

class PodplayerApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // StrictMode first (debug builds only), so it sees everything that follows.
        BuildTypeSetup.install()
        Invariants.handler = BuildTypeSetup.invariantHandler()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as PodplayerApp).graph
