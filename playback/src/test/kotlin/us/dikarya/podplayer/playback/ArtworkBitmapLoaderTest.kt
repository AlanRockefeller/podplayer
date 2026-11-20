/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.imagesandbox.ArtworkBitmaps

/**
 * Media3 shows artwork only from sandbox-produced cache files (CLAUDE.md). The session's bitmap loader is the
 * place Media3 would otherwise fetch an artwork URI itself or decode artwork bytes (for example the picture in
 * an MP3's tags) in the app process.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkBitmapLoaderTest {
    private val ours = Uri.parse("content://us.dikarya.podplayer.artwork/${"a".repeat(64)}.jpg")
    private val bitmap: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val loaded = mutableListOf<Uri>()
    private val source = object : ArtworkBitmaps {
        override fun isArtworkUri(uri: Uri) = uri == ours

        override suspend fun loadBitmap(uri: Uri): Bitmap? {
            loaded += uri
            return if (uri == ours) bitmap else null
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loader = ArtworkBitmapLoader(source, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun anArtworkUriLoadsThroughTheSandboxedCache() {
        assertSame(bitmap, loader.loadBitmap(ours).get(5, TimeUnit.SECONDS))
        assertEquals(listOf(ours), loaded)
    }

    @Test
    fun anyOtherUriFailsWithoutBeingLoaded() {
        for (uri in listOf(
            "https://img.example.test/a.jpg",
            "http://192.168.1.10/a.jpg",
            "file:///data/data/us.dikarya.podplayer/cache/artwork/${"a".repeat(64)}.jpg",
            "data:image/png;base64,iVBORw0KGgo=",
            "content://media/external/images/media/1",
            "android.resource://us.dikarya.podplayer/drawable/x",
        )) {
            val future = loader.loadBitmap(Uri.parse(uri))
            assertThrows(uri, ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
        }
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun artworkBytesAreNeverDecoded() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertThrows(ExecutionException::class.java) { loader.decodeBitmap(png).get(5, TimeUnit.SECONDS) }
        assertTrue(loaded.isEmpty())
    }

    /**
     * Metadata artwork comes from the URI only. (Artwork bytes in metadata, which Media3 fills in from an audio
     * file's tags, are never read: the loader doesn't look at them, and the forbidden-API rule
     * `media3-untrusted-artwork` keeps our own code from putting bytes there, test code included.)
     */
    @Test
    fun metadataArtworkComesFromTheUriOnly() {
        val withUri = MediaMetadata.Builder().setArtworkUri(ours).build()
        assertSame(bitmap, loader.loadBitmapFromMetadata(withUri)!!.get(5, TimeUnit.SECONDS))
        val remote = MediaMetadata.Builder().setArtworkUri(Uri.parse("https://img.example.test/a.jpg")).build()
        assertThrows(ExecutionException::class.java) { loader.loadBitmapFromMetadata(remote)!!.get(5, TimeUnit.SECONDS) }
        assertEquals(null, loader.loadBitmapFromMetadata(MediaMetadata.Builder().build()))
    }

    @Test
    fun onlyTheFormatsTheCacheHoldsAreSupported() {
        assertTrue(loader.supportsMimeType("image/jpeg"))
        assertTrue(loader.supportsMimeType("image/png"))
        for (type in listOf("image/svg+xml", "image/gif", "image/webp", "image/heic", "text/html", "")) {
            assertFalse(type, loader.supportsMimeType(type))
        }
    }

    @Test
    fun aMissingCacheFileFails() {
        val gone = object : ArtworkBitmaps {
            override fun isArtworkUri(uri: Uri) = true

            override suspend fun loadBitmap(uri: Uri): Bitmap? = null
        }
        val future = ArtworkBitmapLoader(gone, scope).loadBitmap(ours)
        assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
    }
}
