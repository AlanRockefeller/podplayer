/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.imagesandbox.cache.ImageCache
import us.dikarya.podplayer.core.imagesandbox.client.ImageDecodeSandbox
import us.dikarya.podplayer.core.imagesandbox.client.SandboxResult
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate

/**
 * The sandbox decodes one image at a time. Fetched bodies (up to the image limit each) must not pile up waiting
 * for it: at most four images are between fetch and cache at once, so memory stays bounded.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkPipelineBoundTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val routes = Routes()
    private val server = MockWebServer().apply { dispatcher = routes }

    @Before
    fun setUp() = server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun tearDown() = server.close()

    @Test
    fun fetchedImagesWaitingForTheSandboxAreBounded() = runBlocking<Unit> {
        val png = TestImages.png()
        repeat(8) { routes.serve("/$it.png", png) }
        val waiting = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val sandbox = ImageDecodeSandbox {
            waiting.incrementAndGet()
            release.await()
            SandboxResult.Unavailable
        }
        val repository = ArtworkRepository({ NetGate(NetConfig(appVersion = "test"), LoopbackResolver()) }, sandbox, ImageCache({ File(temp.root, "artwork") }, { 0L }))
        val requests = (0 until 8).map { i -> async(Dispatchers.Default) { repository.artwork("http://$IMAGE_HOST:${server.port}/$i.png", IMAGE_HOST) } }
        withTimeout(10_000) { while (waiting.get() < 4) delay(20) }
        delay(500) // give any further fetch time to finish and queue up for the sandbox
        assertEquals("fetched bodies held while the sandbox is busy", 4, routes.requests.size)
        release.complete(Unit)
        withTimeout(10_000) { requests.awaitAll() }
        assertEquals(8, routes.requests.size)
    }
}
