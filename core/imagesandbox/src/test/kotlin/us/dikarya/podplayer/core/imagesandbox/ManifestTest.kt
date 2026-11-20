/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowProcess
import us.dikarya.podplayer.core.imagesandbox.provider.ArtworkUris
import us.dikarya.podplayer.core.imagesandbox.sandbox.ImageSandboxService

/** SPEC §6: the decoder service is isolated, not exported and in its own process; artwork isn't exported. */
@RunWith(AndroidJUnit4::class)
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theSandboxServiceIsIsolatedNotExportedAndInItsOwnProcess() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, ImageSandboxService::class.java), 0)
        assertTrue("isolatedProcess", info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertFalse("exported", info.exported)
        assertEquals("${context.packageName}:imagesandbox", info.processName)
        assertNull("no permission to hand out", info.permission)
    }

    @Test
    fun theArtworkProviderIsNotExportedAndGrantsPerUri() {
        val info = context.packageManager.resolveContentProvider(ArtworkUris.authority(context), PackageManager.GET_META_DATA)!!
        assertFalse("exported", info.exported)
        assertTrue("grantUriPermissions", info.grantUriPermissions)
        assertNull("no writes", info.writePermission)
        assertEquals(context.packageName, info.processName)
    }

    @Test
    fun theSandboxProcessIsRecognised() {
        assertFalse(ImageSandbox.isSandboxProcess())
        ShadowProcess.setIsIsolated()
        assertTrue(ImageSandbox.isSandboxProcess())
    }
}
