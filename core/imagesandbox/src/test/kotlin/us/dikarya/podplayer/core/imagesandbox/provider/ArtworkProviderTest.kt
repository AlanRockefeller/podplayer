/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.provider

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache

/** Artwork URIs for Media3 and Android Auto: content URIs of sandbox-produced cache files, read-only. */
@RunWith(AndroidJUnit4::class)
class ArtworkProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority = ArtworkUris.authority(context)
    private val cache = ImageCache({ ImageCache.dirIn(context.cacheDir) })
    private val bytes = byteArrayOf(9, 8, 7, 6)
    private lateinit var artwork: ArtworkFile

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(ArtworkProvider::class.java, authority)
        artwork = cache.store(ImageCache.keyFor("https://img.example.test/a.jpg"), OutputFormat.Jpeg, bytes)
    }

    private fun uri(path: String) = Uri.parse("content://$authority/$path")

    @Test
    fun aCacheFileIsServedReadOnly() {
        val uri = ArtworkUris.uriFor(context, artwork)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.artwork", uri.authority)
        assertEquals(listOf(artwork.file.name), uri.pathSegments)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
    }

    @Test
    fun writingIsRefused() {
        val uri = ArtworkUris.uriFor(context, artwork)
        for (mode in listOf("w", "wt", "rw", "rwt", "wa")) {
            assertThrows(mode, Exception::class.java) { context.contentResolver.openFileDescriptor(uri, mode) }
        }
        assertThrows(UnsupportedOperationException::class.java) { context.contentResolver.insert(uri, ContentValues()) }
        assertThrows(UnsupportedOperationException::class.java) { context.contentResolver.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { context.contentResolver.update(uri, ContentValues(), null, null) }
        assertArrayEquals(bytes, artwork.file.readBytes())
    }

    @Test
    fun onlyCacheFileNamesResolve() {
        val name = artwork.file.name
        for (path in listOf(
            "../${name}",
            "%2E%2E%2F$name",
            "$name/extra",
            "sub/$name",
            name.uppercase(),
            name.replace(".jpg", ".png"), // our name pattern, but no such file
            "${"0".repeat(64)}.gif",
            "",
        )) {
            assertThrows(path, FileNotFoundException::class.java) { context.contentResolver.openInputStream(uri(path)) }
            assertNull(path, context.contentResolver.getType(uri(path)))
        }
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openInputStream(Uri.parse("content://$authority/$name?x=1"))
        }
    }

    @Test
    fun queryGivesOnlyNameAndSize() {
        context.contentResolver.query(ArtworkUris.uriFor(context, artwork), null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(artwork.file.name, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertNull(context.contentResolver.query(uri("../x"), null, null, null, null))
    }

    @Test
    fun queryFollowsTheRequestedColumns() {
        val uri = ArtworkUris.uriFor(context, artwork)
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
            assertEquals(listOf(OpenableColumns.SIZE), cursor.columnNames.toList())
            assertTrue(cursor.moveToFirst())
            assertEquals(bytes.size.toLong(), cursor.getLong(0))
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, "_data", OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
            // A column we don't offer (here the file path) is left out, never filled in.
            assertEquals(listOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), cursor.columnNames.toList())
            assertTrue(cursor.moveToFirst())
            assertEquals(bytes.size.toLong(), cursor.getLong(0))
            assertEquals(artwork.file.name, cursor.getString(1))
        }
    }

    @Test
    fun artworkUrisAreRecognisedStrictly() {
        val good = ArtworkUris.uriFor(context, artwork)
        assertTrue(ArtworkUris.isArtworkUri(context, good))
        for (other in listOf(
            Uri.parse("https://img.example.test/a.jpg"),
            Uri.fromFile(artwork.file),
            Uri.parse("content://media/external/images/1"),
            Uri.parse("content://${context.packageName}.other/${artwork.file.name}"),
            Uri.parse("content://$authority/${artwork.file.name}/x"),
            Uri.parse("content://$authority/${artwork.file.name}?x=1"),
            Uri.parse("content://$authority/${artwork.file.name}#x"),
            Uri.parse("content://user@$authority/${artwork.file.name}"),
            Uri.parse("content://$authority/x.jpg"),
        )) {
            assertFalse("$other", ArtworkUris.isArtworkUri(context, other))
        }
    }
}
