/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.TestAudio
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.xmlResponse
import us.dikarya.podplayer.core.net.download.RangeDownloader

/**
 * SPEC §9: the storage cap holds for downloads running at the same time. Each one alone fits under it; both
 * together don't, so one of them must stop instead of both finishing over the cap.
 */
@RunWith(AndroidJUnit4::class)
class StorageCapAcrossDownloadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var env: TestEnv? = null

    @After
    fun tearDown() {
        env?.close()
    }

    @Test
    fun twoDownloadsAtOnceStayUnderTheCapTogether() {
        val env = TestEnv(tmp.root).also { env = it }
        val audio = TestAudio.mp3(2_000)
        val cap = audio.size * 3L / 2
        val runner = DownloadRunner(
            repositories = { env.data.awaitRepositories() },
            paths = env.data.paths,
            storageCap = { cap },
            engine = RangeDownloader(env.gate),
            probe = AudioProbe(),
            log = env.log,
            locks = KeyedLocks(),
            clock = env.clock,
        )
        for (path in listOf("/a.mp3", "/b.mp3")) {
            // Slow enough that the two transfers overlap.
            env.routes[path] = {
                MockResponse.Builder().addHeader("ETag", "\"v1\"").addHeader("Content-Type", "audio/mpeg")
                    .body(Buffer().write(audio)).throttleBody(64 * 1024L, 50, TimeUnit.MILLISECONDS).build()
            }
        }
        val feedId = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = {
            xmlResponse(
                feedXml(
                    listOf(
                        Item("g-a", "A", env.url("/a.mp3").toString(), length = null),
                        Item("g-b", "B", env.url("/b.mp3").toString(), length = null, pubDate = "Sun, 08 Nov 2026 10:00:00 GMT"),
                    ),
                ),
            )
        }
        runBlocking { env.refresher.refresh(RefreshRequest.Feed(feedId)) }
        val ids = runBlocking { env.repos().episodes.forFeed(feedId).first().map { it.id } }
        assertEquals(2, ids.size)

        val outcomes = runBlocking {
            ids.forEach { runner.queue(it) }
            withTimeout(60_000) { ids.map { id -> async(Dispatchers.IO) { runner.run(id) } }.awaitAll() }
        }
        assertEquals("$outcomes", 1, outcomes.count { it == DownloadOutcome.Completed })
        val failed = outcomes.filterIsInstance<DownloadOutcome.Failed>().single()
        assertTrue(failed.message, "storage limit" in failed.message)
        val onDisk = File(env.files.filesDir, "media").walk().filter { it.isFile }.sumOf { it.length() }
        assertTrue("$onDisk bytes on disk, cap $cap", onDisk <= cap)
        assertTrue(runBlocking { env.repos().media.usedBytes(exceptEpisodeId = 0) } <= cap)
    }
}
