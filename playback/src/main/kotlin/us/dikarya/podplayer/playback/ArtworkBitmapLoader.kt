/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.imagesandbox.ArtworkBitmaps

/** Why Media3 got no bitmap. */
class ArtworkRefusedException(message: String) : IllegalArgumentException(message)

/**
 * The Media3 session's bitmap loader (S7 sets it with `MediaSession.Builder.setBitmapLoader`). Media3's own
 * loaders fetch any URI with their own data source and decode in the app process, including artwork bytes that
 * the player read from an audio file's tags. This one:
 * - loads only the app's artwork URIs (sandbox-produced cache files, `:core:imagesandbox`), never a remote or
 *   other URI;
 * - never decodes artwork bytes; metadata artwork comes from its URI only;
 * - supports only the two formats the cache holds.
 */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val artwork: ArtworkBitmaps, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType == "image/jpeg" || mimeType == "image/png"

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.immediateFailedFuture(ArtworkRefusedException("Artwork bytes are never decoded in the app process"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (!artwork.isArtworkUri(uri)) {
            return Futures.immediateFailedFuture(ArtworkRefusedException("Not an artwork URI of the image cache"))
        }
        val future = SettableFuture.create<Bitmap>()
        val job = scope.launch {
            try {
                val bitmap = artwork.loadBitmap(uri)
                if (bitmap != null) future.set(bitmap) else future.setException(ArtworkRefusedException("No such artwork"))
            } catch (cancelled: CancellationException) {
                future.cancel(false)
                throw cancelled
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        // A job cancelled before its body ran (the scope had ended) never reaches the handler above.
        job.invokeOnCompletion { cause -> if (cause != null && !future.isDone) future.cancel(false) }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    /** Media3's default reads artwork bytes first. Here only the URI counts. */
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
        metadata.artworkUri?.let(::loadBitmap)
}
