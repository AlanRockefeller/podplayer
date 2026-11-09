/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.OTHER_HOST
import us.dikarya.podplayer.core.jobs.Routes
import us.dikarya.podplayer.core.jobs.TestAudio
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.xmlResponse
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.net.download.DownloadSink
import us.dikarya.podplayer.core.net.download.FileDownloadSink

/** SPEC §9 and LESSONS "Downloads", end to end: a local feed, the real gate, the engine, the probe, Room. */
@RunWith(AndroidJUnit4::class)
class DownloadRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var env: TestEnv? = null
    private val audio = TestAudio.mp3(2_000)

    private fun env(sinkFactory: (File) -> DownloadSink = ::FileDownloadSink): TestEnv =
        TestEnv(tmp.root, sinkFactory = sinkFactory).also { env = it }

    @After
    fun tearDown() {
        env?.close()
    }

    /** Subscribes to a local feed with one episode at [path] and refreshes it. Returns the episode id. */
    private fun TestEnv.episodeAt(path: String, host: String? = null, length: Long? = audio.size.toLong(), type: String = "audio/mpeg"): Long {
        val feedId = subscribe("/feed.xml")
        val url = (if (host == null) url(path) else url(path, host)).newBuilder().query("token=SECRETTOKEN").build().toString()
        routes["/feed.xml"] = { xmlResponse(feedXml(listOf(Item("guid-1", "Episode 1", url, length, type)))) }
        runBlocking { refresher.refresh(RefreshRequest.Feed(feedId)) }
        return runBlocking { repos().episodes.forFeed(feedId).first().single().id }
    }

    /** As `Downloads.enqueue` does: queue the record, then the worker runs it. */
    private fun TestEnv.download(episodeId: Long, cause: StopCause = StopCause.System) = runBlocking {
        runner.queue(episodeId)
        runner.run(episodeId, { _, _ -> }, { cause })
    }

    private fun TestEnv.media(episodeId: Long): MediaFile? = runBlocking { repos().media.get(episodeId) }

    private fun TestEnv.fileOf(episodeId: Long): File? = media(episodeId)?.relativePath?.let { data.paths.resolve(it) }

    private fun TestEnv.events(episodeId: Long): List<DownloadEvent> =
        runBlocking { repos().logs.downloadLog(episodeId, 100).first().map { it.event }.reversed() }

    private fun TestEnv.messages(episodeId: Long): List<String> =
        runBlocking { repos().logs.downloadLog(episodeId, 100).first().mapNotNull { it.message } }

    @Test
    fun endToEndSubscribeRefreshAndDownload() {
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3")
        assertEquals(DownloadOutcome.Completed, env.download(id))

        val media = env.media(id)!!
        val feedId = runBlocking { env.repos().episodes.get(id)!!.feedId }
        assertEquals(DownloadState.Complete, media.state)
        assertEquals("media/$feedId/$id.mp3", media.relativePath)
        assertEquals(audio.size.toLong(), media.bytesDownloaded)
        val expected = TestAudio.mp3DurationMs(2_000)
        assertTrue("${media.probedDurationMs}", kotlin.math.abs(media.probedDurationMs!! - expected) < expected / 20)
        assertArrayEquals(audio, env.fileOf(id)!!.readBytes())
        assertEquals(listOf(DownloadEvent.Queued, DownloadEvent.Started, DownloadEvent.Completed), env.events(id))
        val actions = runBlocking { env.repos().actions.unsynced(10) }
        assertTrue(actions.any { it.episodeId == id && it.kind == EpisodeActionKind.Downloaded })
        assertEquals(DownloadOutcome.AlreadyComplete, env.download(id))
        assertEquals("a finished download isn't fetched again", 1, env.routes.count("/ep1.mp3"))
    }

    @Test
    fun aDroppedConnectionResumesWithRangeAndIfRange() {
        val env = env()
        var calls = 0
        env.routes["/ep1.mp3"] = { request ->
            if (calls++ == 0) {
                MockResponse.Builder().addHeader("ETag", "\"v1\"").body(Buffer().write(audio))
                    .onResponseBody(SocketEffect.CloseSocket()).build()
            } else {
                Routes.fileResponse(request, audio, "\"v1\"", "audio/mpeg", rangeSupport = true)
            }
        }
        val id = env.episodeAt("/ep1.mp3")
        assertTrue(env.download(id) is DownloadOutcome.Retry)
        val partial = env.media(id)!!
        assertEquals(DownloadState.Paused, partial.state)
        assertTrue(partial.bytesDownloaded in 1 until audio.size.toLong())
        assertEquals(partial.bytesDownloaded, env.fileOf(id)!!.length())

        assertEquals(DownloadOutcome.Completed, env.download(id))
        val resume = env.routes.requestsTo("/ep1.mp3")[1]
        assertEquals("bytes=${partial.bytesDownloaded}-", resume.headers["Range"])
        assertEquals("\"v1\"", resume.headers["If-Range"])
        assertArrayEquals(audio, env.fileOf(id)!!.readBytes())
        assertTrue(DownloadEvent.Resumed in env.events(id))
    }

    @Test
    fun aServerIgnoringRangeRestartsAndSaysSo() {
        val env = env()
        var calls = 0
        env.routes["/ep1.mp3"] = { request ->
            if (calls++ == 0) {
                MockResponse.Builder().addHeader("ETag", "\"v1\"").body(Buffer().write(audio))
                    .onResponseBody(SocketEffect.CloseSocket()).build()
            } else {
                Routes.fileResponse(request, audio, "\"v1\"", "audio/mpeg", rangeSupport = false)
            }
        }
        val id = env.episodeAt("/ep1.mp3")
        env.download(id)
        assertEquals(DownloadOutcome.Completed, env.download(id))
        assertArrayEquals(audio, env.fileOf(id)!!.readBytes())
        assertTrue(DownloadEvent.Restarted in env.events(id))
        assertTrue(env.messages(id).joinToString(), env.messages(id).any { "ignored" in it })
    }

    @Test
    fun aWrongDeclaredLengthIsAcceptedWhenTheProbeSucceeds() {
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3", length = 99_999_999L, type = "application/octet-stream")
        assertEquals(DownloadOutcome.Completed, env.download(id))
        assertEquals(audio.size.toLong(), env.media(id)!!.bytesDownloaded)
    }

    @Test
    fun aChunkedResponseWithoutAnyLengthIsAccepted() {
        val env = env()
        env.routes["/ep1.mp3"] = { MockResponse.Builder().chunkedBody(Buffer().write(audio), 8_192).build() }
        val id = env.episodeAt("/ep1.mp3", length = null)
        assertEquals(DownloadOutcome.Completed, env.download(id))
    }

    @Test
    fun anUnreadableFileIsRejectedAndDeleted() {
        val env = env()
        env.routes.file("/ep1.mp3", Random(7).nextBytes(150_000))
        val id = env.episodeAt("/ep1.mp3")
        val outcome = env.download(id)
        assertTrue("$outcome", outcome is DownloadOutcome.Failed)
        val media = env.media(id)!!
        assertEquals(DownloadState.Failed, media.state)
        assertNull(media.completedAt)
        assertFalse("the file is removed", File(env.files.filesDir, "media").walk().any { it.isFile })
        assertTrue(env.messages(id).joinToString(), env.messages(id).any { "audio" in it })
    }

    @Test
    fun anImageOrAnEmptyFileIsRejected() {
        for (bytes in listOf(TestAudio.png, ByteArray(0))) {
            env?.close()
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val env = env()
            env.routes.file("/ep1.mp3", bytes)
            val id = env.episodeAt("/ep1.mp3")
            assertTrue(env.download(id) is DownloadOutcome.Failed)
            assertEquals(DownloadState.Failed, env.media(id)!!.state)
        }
    }

    @Test
    fun aFullDiskLeavesNothingMarkedComplete() {
        val env = env { file -> DiskFullSink(file, failAfter = 50_000) }
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3")
        val outcome = env.download(id)
        assertTrue("$outcome", outcome is DownloadOutcome.Failed)
        val media = env.media(id)!!
        assertEquals(DownloadState.Failed, media.state)
        assertEquals(0L, media.bytesDownloaded)
        assertFalse("the partial file is removed to free space", env.data.paths.resolve(media.relativePath)!!.exists())
        assertTrue(env.messages(id).joinToString(), env.messages(id).any { "storage" in it })
        assertTrue(runBlocking { env.repos().actions.unsynced(10) }.none { it.kind == EpisodeActionKind.Downloaded })
    }

    @Test
    fun theStorageCapStopsADownload() {
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3")
        val other = env.episodeAtSecondFeed()
        // Another download already fills the cap but for 1,000 bytes.
        runBlocking {
            env.repos().media.upsert(
                MediaFile(other, "media/1/$other.mp3", DownloadState.Complete, AppSettings.DEFAULT_STORAGE_CAP_BYTES - 1_000, null, 1, 1),
            )
        }
        val outcome = env.download(id)
        assertTrue("$outcome", outcome is DownloadOutcome.Failed)
        assertTrue(env.messages(id).joinToString(), env.messages(id).any { "storage limit" in it })
        assertEquals(DownloadState.Failed, env.media(id)!!.state)
        assertFalse(env.fileOf(id)!!.exists())
    }

    private fun TestEnv.episodeAtSecondFeed(): Long {
        val feedId = subscribe("/second.xml")
        routes["/second.xml"] = { xmlResponse(feedXml(listOf(Item("guid-2", "Other", url("/other.mp3").toString())))) }
        runBlocking { refresher.refresh(RefreshRequest.Feed(feedId)) }
        return runBlocking { repos().episodes.forFeed(feedId).first().single().id }
    }

    @Test
    fun anEnclosureOnAPrivateHostNobodyApprovedIsRefused() {
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3", host = OTHER_HOST)
        val outcome = env.download(id)
        assertTrue("$outcome", outcome is DownloadOutcome.Failed)
        assertEquals(0, env.routes.count("/ep1.mp3"))
        assertTrue(env.messages(id).joinToString(), env.messages(id).any { "private network" in it })
        assertFalse(File(env.files.filesDir, "media").walk().any { it.isFile && it.length() > 0 })
    }

    @Test
    fun stoppingKeepsThePartialFileAsPaused() {
        for (cause in listOf(StopCause.Owner, StopCause.ForegroundTimeout)) {
            env?.close()
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val env = env()
            env.routes["/ep1.mp3"] = {
                MockResponse.Builder().addHeader("ETag", "\"v1\"").body(Buffer().write(audio))
                    .throttleBody(16_384, 100, TimeUnit.MILLISECONDS).build()
            }
            val id = env.episodeAt("/ep1.mp3")
            val started = CompletableDeferred<Unit>()
            runBlocking {
                env.runner.queue(id)
                val job = async(Dispatchers.IO) {
                    env.runner.run(id, onProgress = { bytes, _ -> if (bytes > 0) started.complete(Unit) }, stopCause = { cause })
                }
                withTimeout(10_000) { started.await() }
                job.cancel()
                runCatching { job.await() }
            }
            val media = env.media(id)!!
            assertEquals("$cause", DownloadState.Paused, media.state)
            assertTrue("$cause", media.bytesDownloaded > 0)
            assertEquals(media.bytesDownloaded, env.fileOf(id)!!.length())
            val text = env.messages(id).joinToString()
            if (cause == StopCause.ForegroundTimeout) assertTrue(text, "6-hour" in text) else assertTrue(text, "You stopped" in text)
        }
    }

    @Test
    fun deletingRemovesTheFileAndTheRecord() {
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3")
        env.download(id)
        val file = env.fileOf(id)!!
        runBlocking { env.runner.delete(id) }
        assertFalse(file.exists())
        assertNull(env.media(id))
        assertEquals(DownloadEvent.Deleted, env.events(id).last())
        assertTrue(runBlocking { env.repos().actions.unsynced(10) }.any { it.kind == EpisodeActionKind.Deleted })
    }

    @Test
    fun serverErrorsRetryAndMissingFilesFail() {
        val env = env()
        env.routes["/ep1.mp3"] = { MockResponse.Builder().code(503).build() }
        val id = env.episodeAt("/ep1.mp3")
        assertTrue(env.download(id) is DownloadOutcome.Retry)
        env.routes["/ep1.mp3"] = { MockResponse.Builder().code(404).build() }
        assertTrue(env.download(id) is DownloadOutcome.Failed)
    }

    @Test
    fun anUnknownEpisodeIsGone() {
        assertEquals(DownloadOutcome.Gone, env().download(9_999))
    }

    @Test
    fun aRunWithoutAQueuedRecordDoesNothing() {
        // For example a worker that starts just after the owner deleted the download.
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3")
        assertEquals(DownloadOutcome.Gone, runBlocking { env.runner.run(id) })
        assertEquals(0, env.routes.count("/ep1.mp3"))
        assertNull(env.media(id))
    }

    @Test
    fun queueingTwiceLogsItOnce() {
        val env = env()
        val id = env.episodeAt("/ep1.mp3")
        assertTrue(runBlocking { env.runner.queue(id) })
        assertTrue(runBlocking { env.runner.queue(id) })
        assertEquals(listOf(DownloadEvent.Queued), env.events(id))
    }

    @Test
    fun queueingAFinishedDownloadChangesNothing() {
        val env = env()
        env.routes.file("/ep1.mp3", audio)
        val id = env.episodeAt("/ep1.mp3")
        env.download(id)
        assertFalse(runBlocking { env.runner.queue(id) })
        assertEquals(DownloadState.Complete, env.media(id)!!.state)
    }

    @Test
    fun noTokenInTheDownloadLog() {
        val env = env()
        env.routes["/ep1.mp3"] = { MockResponse.Builder().code(404).build() }
        val id = env.episodeAt("/ep1.mp3")
        env.download(id)
        env.log.flush()
        val texts = env.messages(id) + File(tmp.root, "logs").walk().filter { it.isFile }.map { it.readText() }.toList()
        for (text in texts) assertFalse(text, "SECRETTOKEN" in text)
    }

    /** Pretends the disk fills up after [failAfter] bytes. */
    private class DiskFullSink(file: File, private val failAfter: Long) : DownloadSink {
        private val real = FileDownloadSink(file)
        private var written = real.length()

        override fun length() = real.length()

        override fun truncate() {
            real.truncate()
            written = 0
        }

        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            if (written + count > failAfter) throw IOException("write failed: ENOSPC (No space left on device)")
            real.write(bytes, offset, count)
            written += count
        }

        override fun close() = real.close()
    }
}
