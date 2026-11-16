/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.appContext
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.refresh.RefreshSummary
import us.dikarya.podplayer.core.jobs.refresh.Refresher
import us.dikarya.podplayer.core.jobs.xmlResponse
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile

/** SPEC §9 "Auto-download": new episodes only, its own network rule, background work, the storage cap. */
@RunWith(AndroidJUnit4::class)
class AutoDownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = appContext()
    private lateinit var workManager: WorkManager
    private lateinit var env: TestEnv
    private lateinit var downloads: Downloads
    private lateinit var auto: AutoDownloader
    private var feedId = 0L

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        env = TestEnv(File(tmp.root, "env"))
        downloads = Downloads(
            workManager = { workManager },
            repositories = { env.data.awaitRepositories() },
            wifiOnly = { env.data.settings.downloadsWifiOnly.first() },
            autoWifiOnly = { env.data.settings.autoDownloadWifiOnly.first() },
            runner = env.runner,
            paths = env.data.paths,
            log = env.log,
        )
        auto = AutoDownloader(
            repositories = { env.data.awaitRepositories() },
            enqueue = { downloads.enqueue(it, auto = true) },
            storageCap = { env.data.settings.storageCapBytes.first() },
            cleanUp = { env.data.settings.cleanUp.first() },
            device = env.data.device,
            log = env.log,
            clock = env.clock,
        )
        feedId = env.subscribe()
    }

    @After
    fun tearDown() {
        env.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private val old = listOf(
        Item("old-1", "Old one", "https://cdn.example.test/old-1.mp3", pubDate = "Sat, 01 Aug 2026 10:00:00 GMT"),
        Item("old-2", "Old two", "https://cdn.example.test/old-2.mp3", pubDate = "Sun, 02 Aug 2026 10:00:00 GMT"),
    )

    /** After the test clock's "now" (2026-10-26). */
    private val new = listOf(
        Item("new-1", "New one", "https://cdn.example.test/new-1.mp3", pubDate = "Sat, 07 Nov 2026 10:00:00 GMT"),
        Item("new-2", "New two", "https://cdn.example.test/new-2.mp3", pubDate = "Sun, 08 Nov 2026 10:00:00 GMT"),
    )

    private fun refresh(items: List<Item>) = runBlocking {
        env.routes["/feed.xml"] = { xmlResponse(feedXml(items)) }
        env.refresher.refresh(RefreshRequest.Feed(feedId))
    }

    private fun id(guid: String): Long = runBlocking { env.repos().episodes.forFeed(feedId).first().single { it.guid == guid }.id }

    private fun work(episodeId: Long): WorkInfo? = workManager.getWorkInfosForUniqueWork(Downloads.workName(episodeId)).get().singleOrNull()

    private fun queued(): Set<Long> = runBlocking {
        env.repos().media.observeAll().first().filter { it.state == DownloadState.Queued }.mapTo(HashSet()) { it.episodeId }
    }

    @Test
    fun onlyEpisodesPublishedAfterItWasTurnedOnAreDownloaded() = runBlocking {
        refresh(old)
        assertEquals("off: nothing", 0, auto.run().queued)
        env.repos().feedSettings.setAutoDownload(feedId, true)
        refresh(old + new)
        val result = auto.run()
        assertEquals(2, result.queued)
        assertEquals(setOf(id("new-1"), id("new-2")), queued())
        for (guid in listOf("new-1", "new-2")) {
            val info = work(id(guid))!!
            assertTrue("background work, marked as auto", Downloads.TAG_AUTO in info.tags)
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
        }
        assertNull("the back catalogue is left alone", work(id("old-1")))
    }

    @Test
    fun anEpisodeIsAutoDownloadedOnlyOnceEvenAfterItsFileIsDeleted() = runBlocking {
        env.repos().feedSettings.setAutoDownload(feedId, true)
        refresh(new)
        assertEquals(2, auto.run().queued)
        downloads.delete(id("new-1"))
        assertEquals("deleted by the owner or clean-up: not fetched again", 0, auto.run().queued)
        assertFalse(id("new-1") in queued())
    }

    @Test
    fun autoDownloadsUseAnyConnectionByDefaultAndTheirOwnWifiSetting() = runBlocking {
        // The manual setting stays at its own default (Wi-Fi only) and doesn't affect auto-downloads.
        assertEquals(true, env.data.settings.downloadsWifiOnly.first())
        env.repos().feedSettings.setAutoDownload(feedId, true)
        refresh(new)
        auto.run()
        assertEquals(NetworkType.CONNECTED, work(id("new-1"))!!.constraints.requiredNetworkType)

        env.data.settings.setAutoDownloadWifiOnly(true)
        downloads.applyNetworkSetting()
        assertEquals(NetworkType.UNMETERED, work(id("new-1"))!!.constraints.requiredNetworkType)
        assertTrue(Downloads.TAG_AUTO in work(id("new-1"))!!.tags)

        // Turning the manual one off doesn't move auto-downloads off Wi-Fi.
        env.data.settings.setDownloadsWifiOnly(false)
        downloads.applyNetworkSetting()
        assertEquals(NetworkType.UNMETERED, work(id("new-1"))!!.constraints.requiredNetworkType)
    }

    @Test
    fun anOwnerDownloadKeepsTheManualNetworkSetting() = runBlocking {
        env.data.settings.setAutoDownloadWifiOnly(false)
        refresh(new)
        downloads.enqueue(id("new-1"))
        val info = work(id("new-1"))!!
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertFalse(Downloads.TAG_AUTO in info.tags)
    }

    @Test
    fun tappingDownloadOnAWaitingAutoDownloadMakesItTheOwners() = runBlocking {
        env.data.settings.setDownloadsWifiOnly(false)
        env.data.settings.setAutoDownloadWifiOnly(true)
        env.repos().feedSettings.setAutoDownload(feedId, true)
        refresh(new)
        auto.run()
        val before = work(id("new-1"))!!
        assertEquals(NetworkType.UNMETERED, before.constraints.requiredNetworkType)
        assertTrue(downloads.enqueue(id("new-1")))
        val after = work(id("new-1"))!!
        assertEquals("the same download, not a second one", before.id, after.id)
        assertEquals(NetworkType.CONNECTED, after.constraints.requiredNetworkType)
        assertFalse(Downloads.TAG_AUTO in after.tags)
    }

    @Test
    fun atTheStorageCapAutoDownloadsStopAndNothingIsDeleted() = runBlocking {
        val cap = AppSettings.MIN_STORAGE_CAP_BYTES
        env.data.settings.setStorageCapBytes(cap)
        refresh(old)
        // A finished download already fills the limit.
        val full = id("old-1")
        val path = env.data.paths.relativePathFor(feedId, full, "mp3")
        val file = env.data.paths.resolve(path)!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(16))
        }
        env.repos().media.upsert(MediaFile(full, path, DownloadState.Complete, cap, cap, 1_000, env.clock.now))
        env.repos().feedSettings.setAutoDownload(feedId, true)
        refresh(old + new)

        val result = auto.run()
        assertEquals(0, result.queued)
        assertTrue(result.stoppedAtCap)
        assertEquals(env.clock.now, env.data.device.autoDownloadStoppedAt.first())
        assertTrue("nothing deleted to make room", file.isFile)
        assertEquals(DownloadState.Complete, env.repos().media.get(full)!!.state)
        assertTrue(queued().isEmpty())
        assertTrue(env.log.let { it.flush(); it.files() }.joinToString("") { it.readText() }.contains("storage limit"))

        // Room again (the owner deleted it): the next run downloads and clears the note.
        downloads.delete(full)
        assertEquals(2, auto.run().queued)
        assertNull(env.data.device.autoDownloadStoppedAt.first())
    }

    @Test
    fun declaredSizesThatWouldPassTheCapStopTheRest() = runBlocking {
        val cap = AppSettings.MIN_STORAGE_CAP_BYTES
        env.data.settings.setStorageCapBytes(cap)
        env.repos().feedSettings.setAutoDownload(feedId, true)
        refresh(new.map { it.copy(length = cap * 2 / 3) })
        val result = auto.run()
        assertEquals("the first fits, the second would pass the limit", 1, result.queued)
        assertTrue(result.stoppedAtCap)
        assertEquals(setOf(id("new-1")), queued())
    }

    @Test
    fun keepStarredDownloadedFetchesStarredEpisodes() = runBlocking {
        refresh(old)
        env.repos().starred.setStarred(id("old-1"), true)
        assertEquals("off by default", 0, auto.run().queued)
        env.data.settings.setCleanUp(CleanUpSettings(keepStarredDownloaded = true))
        assertEquals(1, auto.run().queued)
        assertEquals(setOf(id("old-1")), queued())
        assertNotNull(work(id("old-1")))
    }

    @Test
    fun everyRefreshIsFollowedByAutoDownload() = runBlocking {
        var runs = 0
        val summary = RefreshSummary(1, 1, 0, 0, 2)
        val wrapped = AutoDownloadAfterRefresh(Refresher { summary }) { runs++ }
        assertEquals(summary, wrapped.refresh(RefreshRequest.Due))
        assertEquals(1, runs)
        val failing = AutoDownloadAfterRefresh(Refresher { summary }) { error("boom") }
        assertEquals("a failed auto-download never fails the refresh", summary, failing.refresh(RefreshRequest.All))
    }
}
