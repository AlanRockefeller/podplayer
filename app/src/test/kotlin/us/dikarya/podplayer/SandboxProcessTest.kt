/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

/**
 * Android runs the Application class in the isolated image sandbox process too (SPEC §6). There it must do
 * nothing: no StrictMode, database, WorkManager, crash handler or log files. The app's merged manifest must keep
 * the sandbox service isolated and private.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class) // so Robolectric doesn't start the real app around the test
class SandboxProcessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theApplicationDoesNothingInTheSandboxProcess() {
        ShadowProcess.setIsIsolated()
        val app = PodplayerApp()
        app.onCreate()
        assertFalse("no app graph in the sandbox process", app.isStarted)
    }

    @Test
    fun theMergedManifestKeepsTheSandboxIsolatedAndPrivate() {
        val service = ComponentName(context, "us.dikarya.podplayer.core.imagesandbox.sandbox.ImageSandboxService")
        val info = context.packageManager.getServiceInfo(service, 0)
        assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:imagesandbox", info.processName)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.artwork", 0)!!
        assertFalse(provider.exported)
    }
}
