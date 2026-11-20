/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The twin of `DeveloperScreenAbsentTest` (testRelease): the names it checks really are the screen's. */
class DeveloperScreenPresentTest {
    @Test
    fun debugBuildsOfferTheDeveloperScreen() {
        assertNotNull(BuildTypeSetup.debugScreen)
    }

    @Test
    fun theScreensCodeIsInTheDebugBuild() {
        for (name in DeveloperScreenNames.CLASSES) Class.forName(name)
    }

    @Test
    fun theScreensStringsAreInTheDebugBuild() {
        val strings = Class.forName("${BuildConfig.APPLICATION_ID}.R\$string").fields.map { it.name }
        assertTrue(strings.any { it.startsWith(DeveloperScreenNames.STRING_PREFIX) })
    }
}
