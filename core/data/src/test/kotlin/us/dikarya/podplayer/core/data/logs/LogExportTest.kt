/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.logs

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.DownloadLogEntity
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.db.RefreshLogEntity
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.model.PositionCause
import us.dikarya.podplayer.core.model.PositionChange

/** SPEC §13 / M1-S9: one export of every log, with every URL redacted, written only when asked. */
@RunWith(AndroidJUnit4::class)
class LogExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var db: PodDatabase
    private lateinit var repos: Repositories
    private lateinit var log: AppLog
    private val crashDir by lazy { File(tmp.root, "logs/crash") }
    private var feedId = 0L
    private var episodeId = 0L

    @Before
    fun setUp() = runBlocking {
        db = openDatabase(File(tmp.root, "test.db"))
        repos = Repositories(db, clock) { "device-1" }
        log = AppLog({ File(tmp.root, "logs") }, clock, echo = false)
        feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed(listOf(episode(1))))
        episodeId = repos.episodes.forFeed(feedId).first().single().id
    }

    @After
    fun tearDown() {
        log.close()
        db.close()
    }

    private fun export(maxBytes: Long = LogExport.MAX_BYTES): String {
        val out = ByteArrayOutputStream()
        runBlocking { LogExport(log, { crashDir }, { repos }, "1.2.3-test", clock, maxBytes).writeTo(out) }
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun theExportHoldsEveryLogWithEveryUrlRedacted() = runBlocking {
        log.i("Refresh", "Fetching https://alice:hunter2@feeds.example.com/private.xml?token=APPSECRET#frag")
        log.i("Playback", "Ignored Play from com.example.car: Bluetooth audio just connected")
        crashDir.mkdirs()
        File(crashDir, "crash-20261112-101500.txt").writeText(
            "Podplayer crashed\njava.io.IOException: http://cdn.example.com/a.mp3?sig=CRASHSECRET\n",
        )
        // Rows written by an older version, before the repositories redacted, still leave the phone redacted.
        db.logs().insertRefresh(
            RefreshLogEntity(0, feedId, clock(), RefreshOutcome.Failed, "Couldn't reach https://u:pw@feeds.example.com/x?token=REFRESHSECRET"),
        )
        db.logs().insertDownload(
            DownloadLogEntity(0, episodeId, clock(), DownloadEvent.Failed, "HTTP 403 from https://cdn.example.com/1.mp3?token=DOWNLOADSECRET"),
        )
        repos.logs.addPosition(PositionChange(episodeId = episodeId, at = 0, fromMs = 70_000, toMs = 55_000, cause = PositionCause.SkipBack, streaming = true))

        val text = export()

        for (secret in listOf("APPSECRET", "hunter2", "CRASHSECRET", "REFRESHSECRET", "pw@", "DOWNLOADSECRET", "SECRETTOKEN", "token=", "sig=")) {
            assertFalse("$secret leaked:\n$text", secret in text)
        }
        assertTrue(text, "Podplayer 1.2.3-test" in text)
        assertTrue(text, "https://feeds.example.com/private.xml" in text)
        assertTrue(text, "Ignored Play from com.example.car" in text)
        assertTrue(text, "crash-20261112-101500.txt" in text)
        assertTrue(text, "http://cdn.example.com/a.mp3" in text)
        assertTrue(text, "Couldn't reach https://feeds.example.com/x" in text)
        assertTrue(text, "HTTP 403 from https://cdn.example.com/1.mp3" in text)
        assertTrue(text, "SkipBack" in text && "1:10" in text && "0:55" in text)
        for (heading in listOf(LogExport.APP_HEADING, LogExport.CRASH_HEADING, LogExport.REFRESH_HEADING, LogExport.DOWNLOAD_HEADING, LogExport.POSITION_HEADING)) {
            assertTrue("missing $heading", heading in text)
        }
    }

    @Test
    fun anEmptyPhoneStillExportsTheHeadings() {
        val text = export()
        assertTrue(text, LogExport.APP_HEADING in text)
        assertTrue(text, LogExport.CRASH_HEADING in text)
    }

    @Test
    fun theExportIsCapped() {
        repeat(400) { log.i("Big", "x".repeat(1_000)) }
        val text = export(maxBytes = 50_000)
        assertTrue(text.toByteArray().size <= 50_000 + 200)
        assertTrue(text, LogExport.TRUNCATED in text)
    }

    @Test
    fun theViewerReadsTheNewestAppLinesAndCrashesNewestFirst() = runBlocking {
        repeat(30) { log.i("T", "line $it") }
        crashDir.mkdirs()
        File(crashDir, "crash-20261110-090000.txt").writeText("old https://x.example.com/?k=OLDSECRET")
        File(crashDir, "crash-20261112-090000.txt").writeText("new")
        val export = LogExport(log, { crashDir }, { repos }, "t", clock)
        val lines = export.appLines(maxLines = 10)
        assertEquals(10, lines.size)
        assertTrue(lines.last(), lines.last().endsWith("line 29"))
        val crashes = export.crashes()
        assertEquals(listOf("crash-20261112-090000.txt", "crash-20261110-090000.txt"), crashes.map { it.name })
        assertFalse("OLDSECRET" in crashes.last().text)
    }

    @Test
    fun crashesInTheSameSecondAreListedNewestFirst() = runBlocking {
        // CodeRabbit review (PR 3): name order put the first crash of a second before its "-2" and "-9" before "-10".
        crashDir.mkdirs()
        val names = listOf("crash-20261112-090000.txt") + (2..10).map { "crash-20261112-090000-$it.txt" } + "crash-20261111-090000.txt"
        names.forEach { File(crashDir, it).writeText(it) }
        val export = LogExport(log, { crashDir }, { repos }, "t", clock)
        val expected = (10 downTo 2).map { "crash-20261112-090000-$it.txt" } + "crash-20261112-090000.txt" + "crash-20261111-090000.txt"
        assertEquals(expected, export.crashes().map { it.name })
    }
}
