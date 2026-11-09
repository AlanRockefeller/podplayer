/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.media

import java.io.File
import java.io.IOException

/**
 * Relative media paths (SPEC §7, LESSONS "Storage").
 *
 * The database stores paths relative to the app's private files folder, such as `media/12/345.mp3`, never
 * absolute ones, so files survive Android moving the app's data. Names are generated from ids, never taken from
 * a server. A path read back from the database (which a restored backup may have written) is checked again
 * before use: it must stay inside its folder after resolving `..` and symbolic links.
 */
class MediaPaths(private val filesDir: () -> File) {

    /** The relative path for an episode's download. [extension] is a hint; anything unknown becomes `audio`. */
    fun relativePathFor(feedId: Long, episodeId: Long, extension: String?): String {
        require(feedId > 0 && episodeId > 0) { "ids must be positive" }
        return "$MEDIA_DIR/$feedId/$episodeId.${safeExtension(extension)}"
    }

    /**
     * The file for a stored relative path, or null if the path is unsafe: absolute, empty, with `.` or `..`
     * segments, backslashes or control characters, outside the allowed folders, or leading outside the files
     * folder through a symbolic link. Touches the disk (canonical paths), so call it off the main thread.
     */
    fun resolve(relativePath: String?): File? {
        if (relativePath == null || !isSafeRelative(relativePath)) return null
        return try {
            val base = filesDir().canonicalFile
            val root = File(base, relativePath.substringBefore('/')).canonicalFile
            val file = File(base, relativePath).canonicalFile
            file.takeIf { it.path.startsWith(root.path + File.separator) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        const val MEDIA_DIR = "media"

        /** Top-level folders a stored path may point into. Images join in M1-S6. */
        val ROOTS = setOf(MEDIA_DIR)

        /** Audio container extensions (SPEC §9: M1 accepts audio only). */
        val EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "mp4", "ogg", "oga", "opus", "flac", "wav")

        private const val MAX_LENGTH = 256

        fun safeExtension(extension: String?): String =
            extension?.lowercase()?.takeIf { it in EXTENSIONS } ?: "audio"

        /** The syntactic part of [resolve]: no disk access, safe to call anywhere. */
        fun isSafeRelative(path: String): Boolean {
            if (path.isEmpty() || path.length > MAX_LENGTH) return false
            if (path.startsWith('/') || '\\' in path || path.any { it < ' ' || it == '\u007f' }) return false
            val segments = path.split('/')
            if (segments.size < 2 || segments.first() !in ROOTS) return false
            return segments.all { it.isNotEmpty() && it != "." && it != ".." }
        }
    }
}
