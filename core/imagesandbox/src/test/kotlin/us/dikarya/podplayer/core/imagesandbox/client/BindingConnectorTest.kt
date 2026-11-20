/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.client

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.os.SharedMemory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox
import us.dikarya.podplayer.core.imagesandbox.sandbox.ImageSandboxService

@RunWith(AndroidJUnit4::class)
class BindingConnectorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(app, ImageSandboxService::class.java)
    private val binder = object : IImageSandbox.Stub() {
        override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int) = -1
    }

    /** Runs [block] off the main thread while the main looper delivers the service connection callbacks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> withMainLooper(block: suspend () -> T): T {
        val result: Deferred<T> = CoroutineScope(Dispatchers.IO).async { block() }
        val deadline = System.currentTimeMillis() + 5_000
        while (!result.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result.getCompleted()
    }

    @Test
    fun bindsTheSandboxServiceByItsExplicitComponentAndReusesTheConnection() {
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val connector = BindingConnector(app)
        val first = withMainLooper { connector.connect() }
        assertNotNull(first)
        assertSame(first, withMainLooper { connector.connect() })
        assertEquals("bound by its explicit component", component, shadowOf(app).nextStartedService.component)
        assertNull("bound once", shadowOf(app).nextStartedService)
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
    }

    @Test
    fun disconnectUnbindsAndTheNextConnectBindsAgain() {
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val connector = BindingConnector(app)
        withMainLooper { connector.connect() }
        connector.disconnect()
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        assertNotNull(withMainLooper { connector.connect() })
    }

    @Test
    fun aServiceThatCannotBeBoundGivesNull() {
        shadowOf(app).declareComponentUnbindable(component)
        assertNull(withMainLooper { BindingConnector(app).connect() })
    }
}
