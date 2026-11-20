/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.media

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SPEC §7 and LESSONS "Storage": relative, generated paths that can't escape the media folder. */
class MediaPathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir by lazy { tmp.newFolder("files") }
    private val paths by lazy { MediaPaths { filesDir } }

    @Test
    fun generatedPathsAreRelativeAndUseIds() {
        assertEquals("media/12/345.mp3", paths.relativePathFor(12, 345, "MP3"))
        assertEquals("media/12/345.m4a", paths.relativePathFor(12, 345, "m4a"))
    }

    @Test
    fun serverSuppliedExtensionsAreNeverTrusted() {
        assertEquals("media/1/2.audio", paths.relativePathFor(1, 2, "exe"))
        assertEquals("media/1/2.audio", paths.relativePathFor(1, 2, "mp3/../../x"))
        assertEquals("media/1/2.audio", paths.relativePathFor(1, 2, null))
    }

    @Test
    fun resolvesInsideTheFilesFolder() {
        val file = paths.resolve("media/12/345.mp3")!!
        assertEquals(File(filesDir.canonicalFile, "media/12/345.mp3"), file)
    }

    @Test
    fun unsafePathsResolveToNull() {
        val bad = listOf(
            "/data/data/us.dikarya.podplayer/files/media/1.mp3",
            "media/../databases/podplayer.db",
            "media/./1.mp3",
            "media//1.mp3",
            "media/1/..",
            "media\\1.mp3",
            "media/1.mp3" + Char(0) + ".txt",
            "databases/podplayer.db",
            "shared_prefs/x.xml",
            "media",
            "",
            "media/" + "a".repeat(300),
        )
        for (path in bad) assertNull(path, paths.resolve(path))
        assertNull(paths.resolve(null))
    }

    @Test
    fun aFolderThatCantBeReadDuringTheWalkGivesNoFilesInsteadOfThrowing() {
        // CodeRabbit review (PR 3): Files.walk reports a failure below the root as UncheckedIOException, which
        // escaped and aborted the reconcile after a restore.
        val media = File(filesDir, "media").apply { mkdirs() }
        File(media, "1").mkdirs()
        File(media, "1/2.mp3").writeText("audio")
        val locked = File(media, "9").apply { mkdirs() }
        File(locked, "3.mp3").writeText("audio")
        assertTrue(locked.setReadable(false))
        try {
            assertEquals(emptyList<File>(), paths.mediaFiles())
        } finally {
            locked.setReadable(true)
        }
        assertEquals(2, paths.mediaFiles().size)
    }

    @Test
    fun aSymbolicLinkOutOfTheMediaFolderIsRefused() {
        val media = File(filesDir, "media").apply { mkdirs() }
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(media, "7").toPath(), outside.toPath())
        assertNull(paths.resolve("media/7/1.mp3"))
        assertTrue(paths.resolve("media/8/1.mp3") != null)
    }
}
