/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.work.Configuration
import us.dikarya.podplayer.core.data.logs.CrashHandler
import us.dikarya.podplayer.core.model.Invariants

class PodplayerApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // StrictMode first (debug builds only), so it sees everything that follows.
        BuildTypeSetup.install()
        graph = AppGraph(this)
        // Then the crash handler, so a crash anywhere later leaves a (redacted) crash file.
        CrashHandler.install(graph.log, { graph.files.crashDir }, BuildConfig.VERSION_NAME)
        Invariants.handler = BuildTypeSetup.invariantHandler(graph.log)
        graph.start()
    }

    /** WorkManager initializes on demand with our worker factory (its default initializer is removed). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(graph.workerFactory).build()
}

val Context.appGraph: AppGraph
    get() = (applicationContext as PodplayerApp).graph

/** Ends the process and opens the app again, for example so a staged restore is applied at start. */
fun Context.restartApp() {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
    appGraph.log.i("App", "Restarting")
    appGraph.log.flush()
    startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
