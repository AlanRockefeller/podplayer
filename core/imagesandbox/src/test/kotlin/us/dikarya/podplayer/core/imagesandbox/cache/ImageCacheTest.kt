/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.cache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.dikarya.podplayer.core.imagesandbox.OutputFormat

class ImageCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_793_000_000_000L
    private val dir by lazy { File(temp.root, "artwork") }

    private fun cache(maxBytes: Long = ImageCache.DEFAULT_MAX_BYTES) = ImageCache({ dir }, { now }, maxBytes)

    private val key = ImageCache.keyFor("https://img.example.test/show.jpg?token=SECRET")

    @Test
    fun theKeyIsAHashThatRevealsNothingAboutTheUrl() {
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("SECRET"))
        assertEquals(key, ImageCache.keyFor("https://img.example.test/show.jpg?token=SECRET"))
        assertNotEquals(key, ImageCache.keyFor("https://img.example.test/show.jpg?token=OTHER"))
    }

    @Test
    fun storedFilesAreFoundAndNothingElseIsLeftBehind() {
        val cache = cache()
        assertNull(cache.find(key))
        val bytes = byteArrayOf(1, 2, 3)
        val stored = cache.store(key, OutputFormat.Png, bytes)
        assertEquals("$key.png", stored.file.name)
        assertEquals(OutputFormat.Png, stored.format)
        assertArrayEquals(bytes, stored.file.readBytes())
        assertEquals(stored, cache.find(key))
        assertEquals(listOf("$key.png"), dir.list()!!.filter { it != ImageCache.FAILURES_DIR })
    }

    @Test
    fun storingAnotherFormatReplacesTheOldFile() {
        val cache = cache()
        cache.store(key, OutputFormat.Png, byteArrayOf(1))
        val jpeg = cache.store(key, OutputFormat.Jpeg, byteArrayOf(2))
        assertEquals(jpeg, cache.find(key))
        assertFalse(File(dir, "$key.png").exists())
    }

    @Test
    fun onlyFilesTheCacheMadeAreOwned() {
        val cache = cache()
        val other = ArtworkFile(File(dir, "$key.png"), OutputFormat.Png)
        assertFalse("nothing is owned before the folder is known", cache.owns(other))
        val stored = cache.store(key, OutputFormat.Png, byteArrayOf(1))
        assertTrue(cache.owns(stored))
        assertTrue(cache.owns(other))
        assertFalse(cache.owns(ArtworkFile(File(dir, "../$key.png"), OutputFormat.Png)))
        assertFalse(cache.owns(ArtworkFile(File(temp.root, "$key.png"), OutputFormat.Png)))
        assertFalse(cache.owns(ArtworkFile(File(dir, "evil.png"), OutputFormat.Png)))
        assertFalse(cache.owns(ArtworkFile(File(dir, "$key.gif"), OutputFormat.Png)))
        assertFalse(cache.owns(ArtworkFile(File(dir, "${key.uppercase()}.png"), OutputFormat.Png)))
        assertFalse(cache.owns(ArtworkFile(File(File(dir, "sub"), "$key.png"), OutputFormat.Png)))
    }

    @Test
    fun filesAreLookedUpByNameOnlyWhenTheNameIsOneWeMake() {
        val cache = cache()
        cache.store(key, OutputFormat.Jpeg, byteArrayOf(1))
        assertNotNull(cache.byName("$key.jpg"))
        for (name in listOf("$key.png", "../$key.jpg", "$key.jpg/..", "x.jpg", "$key.gif", "", "..", "/etc/passwd")) {
            assertNull(name, cache.byName(name))
        }
        val link = File(dir, "${"0".repeat(64)}.jpg")
        Files.createSymbolicLink(link.toPath(), File(temp.root, "secret").also { it.writeText("x") }.toPath())
        assertNull("a symbolic link is not a cache file", cache.byName(link.name))
    }

    @Test
    fun failuresAreRememberedAcrossRestartsUntilTheyExpire() {
        cache().rememberFailure(key, "Not an image", forMs = 60_000)
        val restarted = cache()
        assertEquals("Not an image", restarted.failure(key)?.reason)
        now += 60_001
        assertNull(restarted.failure(key))
        assertNull(cache().failure(key))
    }

    @Test
    fun aSuccessClearsTheRememberedFailure() {
        val cache = cache()
        cache.rememberFailure(key, "Timed out", forMs = 60_000)
        cache.store(key, OutputFormat.Png, byteArrayOf(1))
        assertNull(cache.failure(key))
    }

    @Test
    fun damagedFailureRecordsAreIgnored() {
        val cache = cache()
        cache.rememberFailure(key, "x", forMs = 60_000)
        val record = File(File(dir, ImageCache.FAILURES_DIR), key)
        record.writeText("garbage\nwithout a time")
        assertNull(cache.failure(key))
        record.writeBytes(ByteArray(1_000_000) { 'x'.code.toByte() })
        assertNull(cache.failure(key))
        // Reasons are short and single-line whatever the caller passes.
        cache.rememberFailure(key, "line one\nline two" + "y".repeat(5_000), forMs = 60_000)
        val reason = cache.failure(key)!!.reason
        assertFalse(reason.contains('\n'))
        assertTrue(reason.length <= 300)
    }

    @Test
    fun theCacheIsTrimmedOldestFirst() {
        val cache = cache(maxBytes = 3_000)
        val keys = (1..3).map { ImageCache.keyFor("https://img.example.test/$it.jpg") }
        for (k in keys) {
            cache.store(k, OutputFormat.Jpeg, ByteArray(1_000))
            now += 1_000
        }
        // Using the first one makes it the most recent.
        assertNotNull(cache.find(keys[0]))
        now += 1_000
        cache.store(ImageCache.keyFor("https://img.example.test/4.jpg"), OutputFormat.Jpeg, ByteArray(1_000))
        assertNotNull(cache.find(keys[0]))
        assertNull("least recently used goes first", cache.find(keys[1]))
        assertTrue(cache.usageBytes() <= 3_000)
    }

    @Test
    fun clearRemovesEverything() {
        val cache = cache()
        cache.store(key, OutputFormat.Png, byteArrayOf(1))
        cache.rememberFailure(ImageCache.keyFor("https://b.example.test/x"), "x", 60_000)
        cache.clear()
        assertNull(cache.find(key))
        assertEquals(0L, cache.usageBytes())
        assertEquals(0, cache.fileCount())
        assertNull(cache.failure(ImageCache.keyFor("https://b.example.test/x")))
    }

    @Test
    fun aStoreAfterAndroidClearedTheFolderRecreatesIt() {
        val cache = cache()
        cache.store(key, OutputFormat.Png, byteArrayOf(1))
        dir.deleteRecursively()
        val again = cache.store(key, OutputFormat.Png, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), again.file.readBytes())
    }

    @Test
    fun rememberedFailuresAreBoundedAndExpiredOnesPruned() {
        val cache = ImageCache({ dir }, { now }, maxFailures = 3)
        val failuresDir = File(dir, ImageCache.FAILURES_DIR)
        val expiring = ImageCache.keyFor("https://img.example.test/expiring")
        cache.rememberFailure(expiring, "short", 1_000)
        now += 2_000
        // Changing image URLs from a hostile feed: each one fails once and is never asked for again.
        val keys = (1..5).map { ImageCache.keyFor("https://img.example.test/$it") }
        for (k in keys) {
            cache.rememberFailure(k, "x", 60_000)
            now += 1
        }
        val left = failuresDir.list()!!.toSet()
        assertFalse("an expired record is pruned without being asked for", expiring in left)
        assertTrue("${left.size} records", left.size <= 3)
        assertNotNull("the newest failure is kept", cache.failure(keys.last()))
    }

    @Test
    fun artworkFilesDoNotShowTheirPathInLogs() {
        val stored = cache().store(key, OutputFormat.Png, byteArrayOf(1))
        assertFalse(stored.toString().contains(temp.root.path))
    }
}
