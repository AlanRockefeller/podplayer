/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.cache

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import us.dikarya.podplayer.core.imagesandbox.OutputFormat

/**
 * A re-encoded image the sandbox produced and the app checked (SPEC §6). Only [ImageCache] makes these, so
 * holding one means "this file came out of the sandbox"; Coil and Media3 accept nothing else.
 */
class ArtworkFile internal constructor(val file: File, val format: OutputFormat) {
    override fun equals(other: Any?) = other is ArtworkFile && other.file.path == file.path && other.format == format

    override fun hashCode() = file.path.hashCode()

    /** The name is a hash; the folder is left out. */
    override fun toString() = "ArtworkFile(${file.name})"
}

/** A failure remembered for one image URL, so a hostile image isn't fetched and decoded again and again. */
data class RememberedFailure(val reason: String, val until: Long)

/**
 * The image cache: `<cache folder>/artwork/<sha256 of the URL>.png|.jpg`. Files are written only with bytes that
 * came back from the sandbox and passed `OutputCheck`, and atomically (a temporary file renamed into place), so a
 * half-written file is never visible. Total size is capped; the least recently used files go first.
 *
 * Failures are remembered per URL in `failed/<key>` (time and a short reason), and survive restarts. The folder
 * lives in the app's cache, which Android may clear; the images are fetched again then.
 *
 * Every method touches the disk except [owns] and [keyFor]; call them off the main thread.
 */
class ImageCache internal constructor(
    private val dirProvider: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    /** At most this many remembered failures; past it, expired ones go, then the oldest. */
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
) {
    @Volatile
    private var resolved: File? = null
    private val lock = Any()

    /** The folder, created on first use. */
    internal val dir: File
        get() = resolved ?: synchronized(lock) {
            resolved ?: dirProvider().absoluteFile.also { it.mkdirs() }.also { resolved = it }
        }

    private val failuresDir: File get() = File(dir, FAILURES_DIR)

    internal fun find(key: String): ArtworkFile? {
        if (!KEY.matches(key)) return null
        for (format in OutputFormat.entries) {
            val file = File(dir, "$key.${format.extension}")
            if (isPlainFile(file)) {
                file.setLastModified(clock())
                return ArtworkFile(file, format)
            }
        }
        return null
    }

    /** Writes a checked image. Throws [IOException] when the disk refuses. */
    internal fun store(key: String, format: OutputFormat, bytes: ByteArray): ArtworkFile {
        require(KEY.matches(key)) { "not a cache key" }
        // Android may clear the app's cache, folder and all, while this instance lives.
        dir.mkdirs()
        val target = File(dir, "$key.${format.extension}")
        val temp = File(dir, "$key.${format.extension}.tmp")
        try {
            temp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Couldn't move the image into the cache")
        } finally {
            temp.delete()
        }
        target.setLastModified(clock())
        OutputFormat.entries.filter { it != format }.forEach { File(dir, "$key.${it.extension}").delete() }
        File(failuresDir, key).delete()
        trim(keep = target)
        return ArtworkFile(target, format)
    }

    /** Remembers that [key]'s image failed, for [forMs]. [reason] is shortened to one line. */
    internal fun rememberFailure(key: String, reason: String, forMs: Long) {
        if (!KEY.matches(key)) return
        val line = reason.replace(Regex("""\s+"""), " ").take(MAX_REASON)
        try {
            failuresDir.mkdirs()
            File(failuresDir, key).apply {
                writeText("${clock() + forMs}\n$line")
                setLastModified(clock())
            }
        } catch (_: IOException) {
            // Not remembering only means trying again sooner.
        }
        pruneFailures()
    }

    /**
     * Records are otherwise removed only when their URL is asked for again, and a feed can name endless image
     * URLs. Once there are more than [maxFailures], expired records go, then the oldest written (those are only
     * tried again sooner).
     */
    private fun pruneFailures() {
        val records = failuresDir.listFiles()?.filter { KEY.matches(it.name) && isPlainFile(it) }.orEmpty()
        if (records.size <= maxFailures) return
        val now = clock()
        val live = records.filter { record ->
            val until = runCatching { record.readText().substringBefore('\n').toLongOrNull() }.getOrNull()
            if (until == null || until <= now) {
                record.delete()
                false
            } else {
                true
            }
        }
        if (live.size <= maxFailures) return
        live.sortedBy { it.lastModified() }.take(live.size - maxFailures).forEach { it.delete() }
    }

    internal fun failure(key: String): RememberedFailure? {
        if (!KEY.matches(key)) return null
        val record = File(failuresDir, key)
        if (!isPlainFile(record) || record.length() > MAX_RECORD_BYTES) return null
        val text = try {
            record.readText()
        } catch (_: IOException) {
            return null
        }
        val until = text.substringBefore('\n').toLongOrNull() ?: return null
        if (until <= clock()) {
            record.delete()
            return null
        }
        return RememberedFailure(text.substringAfter('\n', "").take(MAX_REASON), until)
    }

    /**
     * Whether [artwork] is one of this cache's files. Only compares names (no disk access), so Coil can ask on
     * the main thread. Before the folder is known nothing can have been stored, so nothing is owned.
     */
    fun owns(artwork: ArtworkFile): Boolean {
        val folder = resolved ?: return false
        val file = artwork.file
        return file.parentFile?.path == folder.path && FILE_NAME.matches(file.name) &&
            file.name.endsWith(".${artwork.format.extension}")
    }

    /** The cache file called [name], if [name] is one of ours and the file exists (for the content provider). */
    internal fun byName(name: String): ArtworkFile? {
        val match = FILE_NAME.matchEntire(name) ?: return null
        val format = OutputFormat.entries.first { it.extension == match.groupValues[1] }
        val file = File(dir, name)
        return if (isPlainFile(file)) ArtworkFile(file, format) else null
    }

    fun usageBytes(): Long = images().sumOf { it.length() }

    fun fileCount(): Int = images().size

    /** Deletes every image and every remembered failure. */
    fun clear() {
        images().forEach { it.delete() }
        failuresDir.listFiles()?.forEach { it.delete() }
    }

    private fun images(): List<File> = dir.listFiles()?.filter { FILE_NAME.matches(it.name) && it.isFile }.orEmpty()

    /** Least recently used first, down to 90 % of the cap; [keep] (just written) is never removed. */
    private fun trim(keep: File) {
        val files = images()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val target = maxBytes * 9 / 10
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            if (file.path == keep.path) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun isPlainFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        const val DIR_NAME = "artwork"
        internal const val FAILURES_DIR = "failed"

        /** 100 MiB: hundreds of shows' artwork at 1,024 px. */
        const val DEFAULT_MAX_BYTES: Long = 100L * 1024 * 1024

        /** Remembered failures kept at most: each is a small file, and a feed can name endless image URLs. */
        const val DEFAULT_MAX_FAILURES = 1_000

        private const val MAX_REASON = 300
        private const val MAX_RECORD_BYTES = 1_024L
        private val KEY = Regex("[0-9a-f]{64}")
        private val FILE_NAME = Regex("[0-9a-f]{64}\\.(png|jpg)")

        /** The cache key: SHA-256 of the URL, so file names say nothing about the feed or its tokens. */
        fun keyFor(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /** The folder inside an app cache folder. */
        fun dirIn(cacheDir: File): File = File(cacheDir, DIR_NAME)
    }
}
