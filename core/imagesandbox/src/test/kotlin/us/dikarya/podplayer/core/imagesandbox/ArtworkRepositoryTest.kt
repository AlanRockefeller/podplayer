/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import android.graphics.BitmapFactory
import android.os.DeadObjectException
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.core.imagesandbox.cache.ArtworkFile
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache
import us.dikarya.podplayer.core.imagesandbox.client.ImageDecodeSandbox
import us.dikarya.podplayer.core.imagesandbox.client.SandboxClient
import us.dikarya.podplayer.core.imagesandbox.ipc.IImageSandbox
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.NetGate

/**
 * The artwork pipeline: gated fetch, sandbox, output check, cache (SPEC §6). The sandbox here is the real
 * decoder behind its real binder; a recording wrapper shows that every untrusted byte goes there and nowhere
 * else, and that nothing reaches the cache (the only thing Coil and Media3 read) unless it passed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val routes = Routes()
    private val server = MockWebServer().apply { dispatcher = routes }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = NetGate(NetConfig(appVersion = "test"), LoopbackResolver())
    private var now = 1_793_000_000_000L
    private val logLines = CopyOnWriteArrayList<String>()
    private val cacheDir by lazy { File(temp.root, "artwork") }

    /** Everything handed to the sandbox, in order. */
    private val sentToSandbox = CopyOnWriteArrayList<ByteArray>()

    private fun recording(inner: ImageDecodeSandbox) = ImageDecodeSandbox { bytes ->
        sentToSandbox += bytes
        inner.decode(bytes)
    }

    private val cache by lazy { ImageCache({ cacheDir }, { now }) }

    private fun repository(sandbox: ImageDecodeSandbox = realSandboxClient(scope), imageCache: ImageCache = cache) =
        ArtworkRepository({ gate }, recording(sandbox), imageCache, log = { logLines += it })

    private fun url(path: String, host: String = IMAGE_HOST) = "http://$host:${server.port}$path"

    @Before
    fun setUp() = server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun ready(result: ArtworkResult): ArtworkFile {
        assertTrue("expected artwork, got $result", result is ArtworkResult.Ready)
        return (result as ArtworkResult.Ready).file
    }

    private fun problem(result: ArtworkResult): ArtworkProblem {
        assertTrue("expected a placeholder, got $result", result is ArtworkResult.Unavailable)
        return (result as ArtworkResult.Unavailable).problem
    }

    @Test
    fun anImageIsFetchedThroughTheGateDecodedInTheSandboxAndCached() = runBlocking<Unit> {
        val served = TestImages.jpeg(2000, 1500)
        routes.serve("/show.jpg", served, type = "image/jpeg")
        val file = ready(repository().artwork(url("/show.jpg"), approvedHost = IMAGE_HOST))
        assertArrayEquals("the sandbox got exactly the fetched bytes", served, sentToSandbox.single())
        assertTrue(cache.owns(file))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.file.path, options)
        assertEquals(1024 to 768, options.outWidth to options.outHeight)
        assertEquals("Podplayer/test (Android)", routes.requests.single().headers["User-Agent"])
    }

    @Test
    fun aCachedImageNeedsNoNetworkAndNoSandbox() = runBlocking<Unit> {
        routes.serve("/show.png", TestImages.png())
        val repository = repository()
        val first = ready(repository.artwork(url("/show.png"), IMAGE_HOST))
        val second = ready(repository.artwork(url("/show.png"), IMAGE_HOST))
        assertEquals(first, second)
        assertEquals(1, routes.requests.size)
        assertEquals(1, sentToSandbox.size)
        assertEquals(first, repository.cached(url("/show.png")))
    }

    @Test
    fun hostileImagesOnlyEverReachTheSandboxAndEndAsPlaceholders() = runBlocking<Unit> {
        val png = TestImages.png()
        val hostile = mapOf(
            "/truncated.png" to png.copyOf(png.size / 2),
            "/oversized.png" to TestImages.handMadePng(9_000, 9_000, rows = 9_000),
            "/bomb.png" to TestImages.handMadePng(20_000, 20_000, rows = 20_000),
            "/huge-jpeg.jpg" to TestImages.jpegHeaderOnly(60_000, 60_000),
            "/garbage-behind-signature.png" to TestImages.PNG_SIGNATURE + TestImages.random(5_000),
        )
        for ((path, bytes) in hostile) routes.serve(path, bytes)
        val repository = repository()
        for ((path, bytes) in hostile) {
            val problem = problem(repository.artwork(url(path), IMAGE_HOST))
            assertTrue("$path: $problem", problem is ArtworkProblem.Refused)
            assertArrayEquals(path, bytes, sentToSandbox.last())
        }
        assertEquals(0, cache.fileCount())
    }

    @Test
    fun labelsAreHintsOnly() = runBlocking<Unit> {
        // Mislabeled real images are fine; mislabeled non-images are refused without bothering the sandbox.
        routes.serve("/a", TestImages.jpeg(), type = "image/png")
        routes.serve("/b", TestImages.png(), type = "application/octet-stream")
        routes.serve("/svg-as-png.png", TestImages.svg, type = "image/png")
        routes.serve("/svg.svg", TestImages.svgWithProlog, type = "image/svg+xml")
        routes.serve("/page-as-jpeg.jpg", TestImages.html, type = "image/jpeg")
        routes.serve("/noise.png", TestImages.random(3_000), type = "image/png")
        val repository = repository()
        ready(repository.artwork(url("/a"), IMAGE_HOST))
        ready(repository.artwork(url("/b"), IMAGE_HOST))
        assertEquals(2, sentToSandbox.size)
        for (path in listOf("/svg-as-png.png", "/svg.svg", "/page-as-jpeg.jpg", "/noise.png")) {
            val problem = problem(repository.artwork(url(path), IMAGE_HOST))
            assertTrue("$path: $problem", problem is ArtworkProblem.NotAnImage)
        }
        assertEquals(2, sentToSandbox.size)
        assertEquals(2, cache.fileCount())
    }

    @Test
    fun hostileAnswersFromTheSandboxAreNeverCached() = runBlocking<Unit> {
        routes.serve("/show.png", TestImages.png())
        for (answer in listOf(
            TestImages.handMadePng(20_000, 20_000, rows = 20_000),
            TestImages.handMadePng(2_000, 2_000, rows = 2_000),
            TestImages.svg,
            TestImages.animatedGif,
        )) {
            val taken = SandboxClient(DirectConnector { writing(answer) }, scope = scope)
            val fresh = ImageCache({ File(temp.root, "c-${answer.size}") }, { now })
            val problem = problem(repository(taken, fresh).artwork(url("/show.png"), IMAGE_HOST))
            assertTrue("$problem", problem is ArtworkProblem.BadOutput)
            assertEquals(0, fresh.fileCount())
        }
    }

    @Test
    fun anImageOverTheSizeLimitIsRefusedByTheGate() = runBlocking<Unit> {
        routes.serve("/big.png", TestImages.png() + ByteArray(SandboxLimits.MAX_INPUT_BYTES))
        val problem = problem(repository().artwork(url("/big.png"), IMAGE_HOST))
        assertEquals(ArtworkProblem.Network(NetError.TooLarge(SandboxLimits.MAX_INPUT_BYTES.toLong())), problem)
        assertTrue(sentToSandbox.isEmpty())
    }

    @Test
    fun privateAddressesAreRefusedUnlessTheOwnerApprovedThatHost() = runBlocking<Unit> {
        routes.serve("/show.png", TestImages.png())
        val refused = problem(repository().artwork(url("/show.png", OTHER_HOST), approvedHost = IMAGE_HOST))
        assertTrue("$refused", refused is ArtworkProblem.Network && refused.error is NetError.BlockedAddress)
        val noApproval = problem(repository().artwork(url("/show.png"), approvedHost = null))
        assertTrue("$noApproval", noApproval is ArtworkProblem.Network && noApproval.error is NetError.BlockedAddress)
        assertTrue("the server saw nothing", routes.requests.isEmpty())
        ready(repository().artwork(url("/show.png", OTHER_HOST), approvedHost = OTHER_HOST))
    }

    @Test
    fun urlsThatAreNotHttpAreRefusedWithoutTouchingTheNetwork() = runBlocking<Unit> {
        val repository = repository()
        assertEquals(ArtworkProblem.NoImage, problem(repository.artwork(null, IMAGE_HOST)))
        assertEquals(ArtworkProblem.NoImage, problem(repository.artwork("  ", IMAGE_HOST)))
        for (bad in listOf(
            "javascript:alert(1)",
            "file:///data/data/us.dikarya.podplayer/databases/podplayer.db",
            "data:image/png;base64,iVBORw0KGgo=",
            "content://us.dikarya.podplayer.artwork/x.png",
            "ftp://$IMAGE_HOST/x.png",
            "not a url",
        )) {
            assertTrue(bad, problem(repository.artwork(bad, IMAGE_HOST)) is ArtworkProblem.BadUrl)
            assertNull(repository.cached(bad))
        }
        assertTrue(routes.requests.isEmpty())
        assertTrue(sentToSandbox.isEmpty())
    }

    @Test
    fun failuresAreRememberedAcrossRestartsAndRetriedLater() = runBlocking<Unit> {
        routes.status("/gone.png", 404)
        assertTrue(problem(repository().artwork(url("/gone.png"), IMAGE_HOST)) is ArtworkProblem.Network)
        assertTrue(problem(repository().artwork(url("/gone.png"), IMAGE_HOST)) is ArtworkProblem.Remembered)
        val restarted = ImageCache({ cacheDir }, { now })
        assertTrue(problem(repository(imageCache = restarted).artwork(url("/gone.png"), IMAGE_HOST)) is ArtworkProblem.Remembered)
        assertEquals(1, routes.requests.size)
        now += 24 * HOUR + 1
        routes.serve("/gone.png", TestImages.png())
        ready(repository().artwork(url("/gone.png"), IMAGE_HOST))
        assertEquals(2, routes.requests.size)
    }

    @Test
    fun aServerErrorIsRetriedSooner() = runBlocking<Unit> {
        routes.status("/busy.png", 503)
        assertTrue(problem(repository().artwork(url("/busy.png"), IMAGE_HOST)) is ArtworkProblem.Network)
        now += HOUR - 1
        assertTrue(problem(repository().artwork(url("/busy.png"), IMAGE_HOST)) is ArtworkProblem.Remembered)
        now += 2
        routes.serve("/busy.png", TestImages.png())
        ready(repository().artwork(url("/busy.png"), IMAGE_HOST))
    }

    @Test
    fun anImageThatCrashesTheSandboxIsRememberedAndNotTriedAgainSoon() = runBlocking<Unit> {
        routes.serve("/crash.png", TestImages.png())
        val dying = object : IImageSandbox.Stub() {
            override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int): Int = throw DeadObjectException()
        }
        val crashing = SandboxClient(DirectConnector { dying }, scope = scope)
        assertEquals(ArtworkProblem.SandboxCrashed, problem(repository(crashing).artwork(url("/crash.png"), IMAGE_HOST)))
        assertTrue(problem(repository(crashing).artwork(url("/crash.png"), IMAGE_HOST)) is ArtworkProblem.Remembered)
        assertEquals(1, sentToSandbox.size)
        assertEquals(1, routes.requests.size)
    }

    @Test
    fun concurrentRequestsForOneImageMakeOneFetch() = runBlocking<Unit> {
        routes.serve("/slow.png", TestImages.png(), delayMs = 300)
        val repository = repository()
        val results = (1..10).map { async(Dispatchers.Default) { repository.artwork(url("/slow.png"), IMAGE_HOST) } }.awaitAll()
        results.forEach { ready(it) }
        assertEquals(1, routes.requests.size)
        assertEquals(1, sentToSandbox.size)
    }

    @Test
    fun logsAndResultsNeverShowTheUrlsSecrets() = runBlocking<Unit> {
        routes.status("/private.png", 403)
        val secretUrl = url("/private.png") + "?token=SECRET123"
        val result = repository().artwork(secretUrl, IMAGE_HOST)
        assertFalse(result.toString().contains("SECRET123"))
        assertTrue(logLines.isNotEmpty())
        assertTrue(logLines.none { "SECRET123" in it })
        assertTrue(logLines.any { "/private.png" in it })
    }

    @Test
    fun cachedNeverFetches() = runBlocking<Unit> {
        routes.serve("/show.png", TestImages.png())
        val repository = repository()
        assertNull(repository.cached(url("/show.png")))
        assertTrue(routes.requests.isEmpty())
        ready(repository.artwork(url("/show.png"), IMAGE_HOST))
        assertNotNull(repository.cached(url("/show.png")))
    }

    /** A sandbox taken over by a hostile image: it answers [answer] whatever it is sent. */
    private fun writing(answer: ByteArray) = object : IImageSandbox.Stub() {
        override fun decode(input: SharedMemory?, output: SharedMemory?, maxSidePx: Int): Int {
            val map = output!!.mapReadWrite()
            map.put(answer)
            SharedMemory.unmap(map)
            return answer.size
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
