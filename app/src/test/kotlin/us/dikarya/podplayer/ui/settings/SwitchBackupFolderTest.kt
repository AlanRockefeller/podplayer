/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §7: choosing another backup folder never leaves the chosen one without its lasting permission. */
class SwitchBackupFolderTest {
    private val steps = mutableListOf<String>()

    private fun switch(old: String?, new: String, saveFails: Boolean = false) = runBlocking {
        switchBackupFolder(
            old = old,
            new = new,
            take = { steps += "take $it" },
            release = { steps += "release $it" },
            save = {
                if (saveFails) throw IOException("settings not written")
                steps += "save $new"
            },
        )
    }

    @Test
    fun theOldFolderIsGivenUpOnlyAfterTheNewOneIsSaved() {
        switch(old = "content://a", new = "content://b")
        assertEquals(listOf("take content://b", "save content://b", "release content://a"), steps)
    }

    @Test
    fun aFailedSaveKeepsTheOldFolderAndGivesBackTheNewOne() {
        val failed = runCatching { switch(old = "content://a", new = "content://b", saveFails = true) }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertEquals(listOf("take content://b", "release content://b"), steps)
    }

    @Test
    fun choosingTheSameFolderAgainKeepsItsPermission() {
        switch(old = "content://a", new = "content://a")
        assertEquals(listOf("take content://a", "save content://a"), steps)
        steps.clear()
        runCatching { switch(old = "content://a", new = "content://a", saveFails = true) }
        assertEquals(listOf("take content://a"), steps)
    }

    @Test
    fun theFirstFolderHasNothingToGiveUp() {
        switch(old = null, new = "content://b")
        assertEquals(listOf("take content://b", "save content://b"), steps)
    }
}
