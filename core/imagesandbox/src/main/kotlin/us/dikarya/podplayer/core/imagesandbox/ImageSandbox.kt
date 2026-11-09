/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache
import us.dikarya.podplayer.core.imagesandbox.client.BindingConnector
import us.dikarya.podplayer.core.imagesandbox.client.ImageDecodeSandbox
import us.dikarya.podplayer.core.imagesandbox.client.SandboxClient
import us.dikarya.podplayer.core.imagesandbox.client.SandboxStats
import us.dikarya.podplayer.core.imagesandbox.coil.SandboxImageLoader
import us.dikarya.podplayer.core.imagesandbox.provider.ArtworkUris
import us.dikarya.podplayer.core.net.NetGate

/** Bitmaps for Media3 (the notification, Android Auto), from artwork URIs only. */
interface ArtworkBitmaps {
    /** Whether [uri] is one of the app's artwork URIs (a sandbox-produced cache file). */
    fun isArtworkUri(uri: Uri): Boolean

    /** The bitmap for an artwork URI, or null for any other URI or when the file is gone. Never fetches. */
    suspend fun loadBitmap(uri: Uri): Bitmap?
}

/** How much the image cache holds. */
data class CacheUsage(val files: Int, val bytes: Long)

/**
 * Everything image-related for `AppGraph` (SPEC §6). Building it touches neither disk nor network.
 *
 * - [artwork]: fetch, sandbox, check, cache.
 * - [imageLoader]: the app's only Coil loader; it loads [ArtworkFile]s and refuses everything else.
 * - [artworkUri] / [cachedArtworkUri]: content URIs for Media3 and Android Auto, never remote URLs.
 * - [loadBitmap]: bitmaps for Media3, from those URIs only.
 */
class ImageSandbox internal constructor(
    context: Context,
    gate: () -> NetGate,
    sandbox: ImageDecodeSandbox,
    log: (String) -> Unit,
) : ArtworkBitmaps {
    /** [scope] lives as long as the process; the sandbox client releases an idle sandbox in it. */
    constructor(context: Context, gate: () -> NetGate, log: (String) -> Unit, scope: CoroutineScope) :
        this(context, gate, SandboxClient(BindingConnector(context.applicationContext), scope = scope), log)

    private val appContext = context.applicationContext

    val cache = ImageCache({ ImageCache.dirIn(appContext.cacheDir) })

    val artwork = ArtworkRepository(gate, sandbox, cache, log)

    val imageLoader: ImageLoader by lazy { SandboxImageLoader.build(appContext, cache) }

    /** Counts of what the sandbox did, for the Developer screen. */
    val stats: StateFlow<SandboxStats> = (sandbox as? SandboxClient)?.stats ?: MutableStateFlow(SandboxStats())

    /** The artwork URI for [url], fetching and decoding it if needed; null shows a placeholder. */
    suspend fun artworkUri(url: String?, approvedHost: String?): Uri? =
        (artwork.artwork(url, approvedHost) as? ArtworkResult.Ready)?.let { uriFor(it.file) }

    /** The artwork URI for [url] if it is cached already; never touches the network. */
    suspend fun cachedArtworkUri(url: String?): Uri? = artwork.cached(url)?.let(::uriFor)

    fun uriFor(file: ArtworkFile): Uri = ArtworkUris.uriFor(appContext, file)

    override fun isArtworkUri(uri: Uri): Boolean = ArtworkUris.isArtworkUri(appContext, uri)

    override suspend fun loadBitmap(uri: Uri): Bitmap? {
        val name = ArtworkUris.nameOf(appContext, uri) ?: return null
        val file = withContext(Dispatchers.IO) { cache.byName(name) } ?: return null
        val request = ImageRequest.Builder(appContext).data(file).allowHardware(false).build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    suspend fun cacheUsage(): CacheUsage = withContext(Dispatchers.IO) { CacheUsage(cache.fileCount(), cache.usageBytes()) }

    /** Deletes the cached artwork and the remembered failures. */
    suspend fun clearCache() {
        withContext(Dispatchers.IO) { cache.clear() }
        imageLoader.memoryCache?.clear()
    }

    companion object {
        /**
         * True in the isolated sandbox process. Android runs the Application class there too; it must do
         * nothing else in that process (no StrictMode, database, WorkManager or log files).
         */
        fun isSandboxProcess(): Boolean = Process.isIsolated()
    }
}
