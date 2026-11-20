/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SPEC §12, CLAUDE.md: the Developer screen exists in debug builds only. These run against the release
 * variant's classes; `DeveloperScreenPresentTest` in `testDebug` checks the same names exist there, so a
 * rename can't make this pass by accident.
 */
class DeveloperScreenAbsentTest {
    @Test
    fun releaseBuildsOfferNoDebugScreen() {
        assertNull(BuildTypeSetup.debugScreen)
    }

    @Test
    fun theScreensCodeIsNotInTheReleaseBuild() {
        for (name in DeveloperScreenNames.CLASSES) {
            val found = runCatching { Class.forName(name) }.isSuccess
            assertEquals("$name must not exist in a release build", false, found)
        }
    }

    @Test
    fun theScreensStringsAreNotInTheReleaseBuild() {
        val strings = Class.forName("${BuildConfig.APPLICATION_ID}.R\$string").fields.map { it.name }
        assertEquals(emptyList<String>(), strings.filter { it.startsWith(DeveloperScreenNames.STRING_PREFIX) })
    }
}
