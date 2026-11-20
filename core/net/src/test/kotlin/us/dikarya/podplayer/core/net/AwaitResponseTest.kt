/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [awaitResponse] closes the response it was handed whatever happens to the reader. */
class AwaitResponseTest {
    private val server = startServer()

    @After
    fun tearDown() {
        server.close()
    }

    /** Holds dispatched tasks until the test runs them, so a coroutine can be cancelled before it starts. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }
    }

    /** Wraps a body so the test can see when it is closed. */
    private class TrackedBody(private val inner: ResponseBody, private val closed: AtomicBoolean) : ResponseBody() {
        private val tracked: BufferedSource by lazy {
            object : ForwardingSource(inner.source()) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = inner.contentType()

        override fun contentLength() = inner.contentLength()

        override fun source() = tracked
    }

    @Test
    fun theResponseIsClosedWhenCancellationComesBeforeTheReaderStarts() = runBlocking {
        server.enqueue(ok("body"))
        val closed = AtomicBoolean(false)
        val client = testGate().client(approvedFeedHost).newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                response.newBuilder().body(TrackedBody(response.body, closed)).build()
            }
            .build()
        val call = client.newCall(Request.Builder().url(server.urlOn(FEED_HOST, "/feed")).build())
        val held = HeldDispatcher()
        val ran = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            call.awaitResponse(held) { ran.set(true) }
        }
        // The headers are in and the reader is queued on the held dispatcher: cancel before it gets a thread.
        val reader = held.tasks.poll(10, TimeUnit.SECONDS)
        assertTrue("the reader was dispatched", reader != null)
        job.cancel()
        reader!!.run()
        while (!job.isCompleted) held.tasks.poll(100, TimeUnit.MILLISECONDS)?.run()
        assertFalse("the reader never ran", ran.get())
        assertTrue("the response was closed anyway", closed.get())
    }
}
