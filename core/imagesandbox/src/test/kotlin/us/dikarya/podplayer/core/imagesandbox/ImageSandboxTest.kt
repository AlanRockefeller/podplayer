/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache
import us.dikarya.podplayer.core.imagesandbox.provider.ArtworkUris
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate

/**
 * What Media3 and Android Auto get (CLAUDE.md: artwork URIs must point at sandbox-produced cache files, never
 * at remote URLs), and the bitmaps they can load.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSandboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val routes = Routes()
    private val server = MockWebServer().apply { dispatcher = routes }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = NetGate(NetConfig(appVersion = "test"), LoopbackResolver())
    private val images by lazy { ImageSandbox(context, { gate }, realSandboxClient(scope), log = {}) }

    @Before
    fun setUp() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun url(path: String) = "http://$IMAGE_HOST:${server.port}$path"

    @Test
    fun artworkUrisAreSandboxProducedCacheFilesOrNothingNeverTheRemoteUrl() {
        routes.serve("/good.jpg", TestImages.jpeg(1500, 1500), type = "image/jpeg")
        routes.serve("/svg.png", TestImages.svg)
        routes.serve("/bomb.png", TestImages.handMadePng(20_000, 20_000, rows = 20_000))
        routes.status("/missing.png", 404)
        val inputs = listOf(
            url("/good.jpg"), url("/svg.png"), url("/bomb.png"), url("/missing.png"),
            "javascript:alert(1)", "data:image/png;base64,iVBORw0KGgo=", "file:///etc/hosts", null,
        )
        assertNull("nothing is cached yet", withMainLooper { images.cachedArtworkUri(url("/good.jpg")) })
        val uris = inputs.associateWith { withMainLooper { images.artworkUri(it, approvedHost = IMAGE_HOST) } }
        val good = uris.getValue(url("/good.jpg"))
        assertNotNull(good)
        assertTrue(ArtworkUris.isArtworkUri(context, good!!))
        assertEquals(listOf(good), uris.values.filterNotNull())
        assertEquals(good, withMainLooper { images.cachedArtworkUri(url("/good.jpg")) })
        // It points at a file the cache holds.
        val name = good.lastPathSegment!!
        assertTrue(ImageCache.dirIn(context.cacheDir).resolve(name).isFile)
    }

    @Test
    fun bitmapsLoadOnlyFromArtworkUris() {
        routes.serve("/good.png", TestImages.png(600, 300, alpha = true))
        val uri = withMainLooper { images.artworkUri(url("/good.png"), approvedHost = IMAGE_HOST) }!!
        val bitmap = withMainLooper { images.loadBitmap(uri) }
        assertNotNull(bitmap)
        assertEquals(600 to 300, bitmap!!.width to bitmap.height)
        val requestsSoFar = routes.requests.size
        for (other in listOf(
            Uri.parse(url("/good.png")),
            Uri.parse("content://${context.packageName}.other/${uri.lastPathSegment}"),
            Uri.parse("content://${ArtworkUris.authority(context)}/${"f".repeat(64)}.png"),
            Uri.fromFile(ImageCache.dirIn(context.cacheDir).resolve(uri.lastPathSegment!!)),
        )) {
            assertFalse(images.isArtworkUri(other) && other.lastPathSegment == uri.lastPathSegment)
            assertNull("$other", withMainLooper { images.loadBitmap(other) })
        }
        assertEquals(requestsSoFar, routes.requests.size)
    }

    @Test
    fun clearingTheCacheRemovesTheArtwork() {
        routes.serve("/good.png", TestImages.png())
        withMainLooper { images.artworkUri(url("/good.png"), approvedHost = IMAGE_HOST) }
        assertEquals(1, withMainLooper { images.cacheUsage().files })
        withMainLooper { images.clearCache() }
        assertEquals(0, withMainLooper { images.cacheUsage().files })
        assertNull(withMainLooper { images.cachedArtworkUri(url("/good.png")) })
    }
}
