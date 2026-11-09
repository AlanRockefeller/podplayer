/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.coil

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.core.imagesandbox.OutputFormat
import us.dikarya.podplayer.core.imagesandbox.TestImages
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache
import us.dikarya.podplayer.core.imagesandbox.withMainLooper

/**
 * The app's one Coil loader (CLAUDE.md: Coil may only load cache files the sandbox produced; no network
 * fetching). Every other kind of request is refused before Coil fetches or decodes anything.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SandboxImageLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cache = ImageCache({ ImageCache.dirIn(context.cacheDir) })
    private val loader: ImageLoader = SandboxImageLoader.build(context, cache)
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) =
                MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(TestImages.png())).build()
        }
    }

    @After
    fun tearDown() = server.close()

    private fun load(data: Any, configure: ImageRequest.Builder.() -> Unit = {}): ImageResult = withMainLooper {
        loader.execute(ImageRequest.Builder(context).data(data).allowHardware(false).apply(configure).build())
    }

    private fun assertRefused(data: Any, configure: ImageRequest.Builder.() -> Unit = {}) {
        val result = load(data, configure)
        assertTrue("$data: $result", result is ErrorResult)
        assertTrue("$data: ${(result as ErrorResult).throwable}", result.throwable is RefusedImageSourceException)
    }

    private fun stored(): ArtworkFile =
        cache.store(ImageCache.keyFor("https://img.example.test/a.png"), OutputFormat.Png, TestImages.png(120, 80, alpha = true))

    @Test
    fun aSandboxProducedCacheFileLoads() {
        val result = load(stored())
        assertTrue("$result", result is SuccessResult)
        assertEquals(120, (result as SuccessResult).image.width)
        assertEquals(80, result.image.height)
    }

    @Test
    fun networkUrlsAreRefusedAndNothingIsFetched() {
        val url = server.url("/a.png")
        assertRefused(url.toString())
        assertRefused(Uri.parse(url.toString()))
        assertRefused(url)
        assertRefused(url.toUri())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun everyOtherKindOfSourceIsRefused() {
        val artwork = stored()
        val pngBytes = TestImages.png()
        for (data in listOf(
            "data:image/png;base64,iVBORw0KGgo=",
            "content://us.dikarya.podplayer.artwork/${artwork.file.name}",
            Uri.fromFile(artwork.file),
            "file://${artwork.file.path}",
            artwork.file, // the right file, but not as an ArtworkFile
            artwork.file.path,
            pngBytes,
            ByteBuffer.wrap(pngBytes),
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
            android.R.drawable.ic_delete,
        )) {
            assertRefused(data)
        }
    }

    @Test
    fun anArtworkFileOutsideTheCacheIsRefused() {
        stored()
        val elsewhere = File(context.filesDir, "${"a".repeat(64)}.png").also { it.writeBytes(TestImages.png()) }
        assertRefused(ArtworkFile(elsewhere, OutputFormat.Png))
        assertRefused(ArtworkFile(File(cache.dir, "../${"b".repeat(64)}.png"), OutputFormat.Png))
    }

    @Test
    fun requestsThatBringTheirOwnFetcherOrDecoderAreRefused() {
        val artwork = stored()
        assertRefused(artwork) { fetcherFactory(NoFetcher) }
        assertRefused(artwork) { decoderFactory(NoDecoder) }
    }

    @Test
    fun theLoaderHasNoNetworkFetcherAtAll() {
        val names = loader.components.fetcherFactories.map { it.first.javaClass.name } +
            loader.components.decoderFactories.map { it.javaClass.name }
        assertTrue(names.isNotEmpty())
        assertTrue(names.toString(), names.none { "network" in it.lowercase() || "okhttp" in it.lowercase() })
        assertEquals(null, loader.diskCache)
    }

    private object NoFetcher : Fetcher.Factory<ArtworkFile> {
        override fun create(data: ArtworkFile, options: Options, imageLoader: ImageLoader): Fetcher? = null
    }

    private object NoDecoder : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? = null
    }
}
