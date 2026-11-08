/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderCheckTest {
    @Test
    fun acceptsTheExhibitANotice() {
        assertTrue(LicenseHeader.hasNotice(Samples.text("Good.kt")))
    }

    @Test
    fun acceptsWindowsLineEndings() {
        assertTrue(LicenseHeader.hasNotice(Samples.text("Good.kt").replace("\n", "\r\n")))
    }

    @Test
    fun rejectsAFileWithoutTheNotice() {
        assertFalse(LicenseHeader.hasNotice(Samples.text("MissingHeader.kt")))
    }

    @Test
    fun rejectsTheNoticeAfterThePackageLine() {
        assertFalse(LicenseHeader.hasNotice(Samples.text("HeaderAfterPackage.kt")))
    }

    @Test
    fun rejectsAnAlteredNotice() {
        assertFalse(LicenseHeader.hasNotice(Samples.text("AlteredHeader.kt")))
    }

    @Test
    fun rejectsTheNoticeAtTheEndOfTheFile() {
        assertFalse(LicenseHeader.hasNotice(Samples.text("HeaderAtEnd.kt")))
    }

    @Test
    fun rejectsTheNoticeRunningIntoCode() {
        assertFalse(LicenseHeader.hasNotice(LicenseHeader.NOTICE + "package sample"))
    }

    @Test
    fun rejectsAnEmptyFile() {
        assertFalse(LicenseHeader.hasNotice(""))
    }

    @Test
    fun noticeIsTheMozillaExhibitAText() {
        val expected = listOf(
            "/*",
            " * This Source Code Form is subject to the terms of the Mozilla Public",
            " * License, v. 2.0. If a copy of the MPL was not distributed with this",
            " * file, You can obtain one at https://mozilla.org/MPL/2.0/.",
            " */",
        ).joinToString("\n")
        assertEquals(expected, LicenseHeader.NOTICE)
    }

    @Test
    fun checksEverySourceFileType() {
        assertEquals(setOf("kt", "kts", "java", "aidl"), LicenseHeader.EXTENSIONS)
    }
}
