/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.coil

import android.content.Context
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.map.Mapper
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.Options
import coil3.serviceLoaderEnabled
import java.io.File
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache

/** Why the app's Coil loader refused a request. */
class RefusedImageSourceException(message: String) : IllegalArgumentException(message)

/**
 * The app's only Coil `ImageLoader` (CLAUDE.md: Coil may only load cache files the sandbox produced; no
 * network fetching). The app installs it as Coil's singleton, so a stray `AsyncImage(model = url)` hits the
 * same guard.
 *
 * - No network fetcher: none is on the classpath (Coil's network artifacts are banned by the closed-dependency
 *   and forbidden-API checks), and Coil's service loader is off, so nothing can register one by being added.
 * - No disk cache: the image cache is ours.
 * - The first interceptor refuses every request whose data isn't an [ArtworkFile] of [cache] (URLs, `data:`,
 *   content and file URIs, plain files and paths, bytes, bitmaps, resources) or that brings its own fetcher or
 *   decoder, before Coil maps, fetches or decodes anything. Coil then decodes only files the sandbox produced
 *   and `OutputCheck` passed.
 */
object SandboxImageLoader {
    fun build(context: Context, cache: ImageCache): ImageLoader =
        ImageLoader.Builder(context.applicationContext)
            .serviceLoaderEnabled(false)
            .diskCache(null)
            .components {
                add(CacheOnlyInterceptor(cache))
                add(ArtworkFileMapper, ArtworkFile::class)
            }
            .build()
}

internal class CacheOnlyInterceptor(private val cache: ImageCache) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        val problem = when {
            data !is ArtworkFile -> "only sandbox-produced cache files are loaded, not ${data.javaClass.simpleName}"
            !cache.owns(data) -> "not a file of the image cache"
            request.fetcherFactory != null -> "requests can't bring their own fetcher"
            request.decoderFactory != null -> "requests can't bring their own decoder"
            else -> null
        }
        return if (problem == null) chain.proceed() else ErrorResult(request.error(), request, RefusedImageSourceException(problem))
    }
}

internal object ArtworkFileMapper : Mapper<ArtworkFile, File> {
    override fun map(data: ArtworkFile, options: Options): File = data.file
}
