/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache

/**
 * Artwork URIs (SPEC §11: "content URIs of sandbox-produced cache files only"): `content://<package>.artwork/
 * <sha256>.png|.jpg`. Media3's notification and Android Auto get these, never a remote URL.
 */
object ArtworkUris {
    fun authority(context: Context): String = context.packageName + ".artwork"

    fun uriFor(context: Context, artwork: ArtworkFile): Uri =
        Uri.Builder().scheme("content").authority(authority(context)).appendPath(artwork.file.name).build()

    /** Whether [uri] is exactly one of ours: the right authority, one path segment with a cache file name. */
    fun isArtworkUri(context: Context, uri: Uri): Boolean = nameOf(context, uri) != null

    internal fun nameOf(context: Context, uri: Uri): String? {
        if (uri.scheme != "content" || uri.encodedAuthority != authority(context)) return null
        if (uri.encodedQuery != null || uri.encodedFragment != null) return null
        val path = uri.encodedPath ?: return null
        val name = path.removePrefix("/")
        return name.takeIf { NAME.matches(it) }
    }

    private val NAME = Regex("[0-9a-f]{64}\\.(png|jpg)")
}

/**
 * Serves the image cache read-only. Not exported (`grantUriPermissions` lets the app hand out a read grant
 * for a single URI, as Android Auto needs). Anything that isn't a current cache file is "not found"; nothing
 * can be written, inserted, updated or deleted.
 */
class ArtworkProvider : ContentProvider() {
    private val cache by lazy { ImageCache({ ImageCache.dirIn(requireNotNull(context).cacheDir) }) }

    override fun onCreate(): Boolean = true

    private fun artwork(uri: Uri): ArtworkFile? {
        val name = ArtworkUris.nameOf(requireNotNull(context), uri) ?: return null
        return cache.byName(name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val artwork = artwork(uri) ?: throw FileNotFoundException("No such artwork")
        return ParcelFileDescriptor.open(artwork.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = artwork(uri)?.format?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val artwork = artwork(uri) ?: return null
        val values = mapOf<String, Any>(OpenableColumns.DISPLAY_NAME to artwork.file.name, OpenableColumns.SIZE to artwork.file.length())
        // The requested columns, in their order; ones we don't offer are left out. None asked for: both.
        val columns = projection?.filter { it in values }?.distinct() ?: values.keys.toList()
        return MatrixCursor(columns.toTypedArray()).apply { addRow(columns.map { values.getValue(it) }) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Artwork is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only")
}
