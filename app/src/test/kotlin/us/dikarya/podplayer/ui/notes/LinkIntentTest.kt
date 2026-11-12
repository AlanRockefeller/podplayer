/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.notes

import android.app.Application
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** CLAUDE.md: links shown to the owner open only for http, https and mailto, checked again right before opening. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LinkIntentTest {
    @Test
    fun webLinksOpenInTheBrowserOnly() {
        val intent = linkIntent("https://example.com/page?x=1")!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/page?x=1", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue("no grants to the target", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0)
    }

    @Test
    fun mailLinksOpenAMailApp() {
        val intent = linkIntent("mailto:host@example.com")!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:host@example.com", intent.dataString)
    }

    @Test
    fun everyOtherSchemeIsRefused() {
        for (url in listOf("javascript:alert(1)", "java\tscript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "content://x/y", "tel:123", "", "https:")) {
            assertNull(url, linkIntent(url))
        }
    }
}
