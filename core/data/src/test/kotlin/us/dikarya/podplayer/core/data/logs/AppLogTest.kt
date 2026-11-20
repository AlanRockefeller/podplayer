/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.logs

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.appContext

@RunWith(AndroidJUnit4::class)
class AppLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private val dir by lazy { File(tmp.root, "logs") }
    private val log by lazy { AppLog({ dir }, clock, maxFileBytes = 4_096, fileCount = 3, echo = false) }

    @After
    fun tearDown() = log.close()

    private fun allText(): String {
        log.flush()
        return log.files().joinToString("\n") { it.readText() }
    }

    @Test
    fun aFolderAnotherThreadMadeFirstStillGetsTheLine() {
        // LogExportTest lost its first line once: the crash folder (inside the log folder) was made on another
        // thread between the writer's "is it there?" and its mkdirs(), which then said false.
        val racing = object : File(tmp.root, "raced") {
            override fun mkdirs(): Boolean {
                super.mkdirs()
                return false
            }
        }
        val raced = AppLog({ racing }, clock, echo = false)
        raced.i("Refresh", "first line")
        raced.close()
        assertTrue(File(racing, "app.log").readText().contains("first line"))
    }

    @Test
    fun linesHaveTimeLevelTagAndMessage() {
        log.i("Refresh", "3 new episodes")
        val text = allText()
        assertTrue(text, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z I Refresh: 3 new episodes""").containsMatchIn(text))
    }

    @Test
    fun urlsInMessagesAndExceptionsAreRedacted() {
        log.w("Feed", "Fetching https://alice:hunter2@feeds.example.com/private.xml?token=SECRET#frag")
        log.e("Feed", "failed", IOException("timeout for http://cdn.example.com/a.mp3?sig=SECRET2"))
        val text = allText()
        assertFalse(text, "SECRET" in text)
        assertFalse(text, "hunter2" in text)
        assertTrue(text, "https://feeds.example.com/private.xml" in text)
        assertTrue(text, "java.io.IOException: timeout for http://cdn.example.com/a.mp3" in text)
    }

    @Test
    fun newlinesInMessagesCannotForgeEntries() {
        log.i("Feed", "Title: Evil\n2026-01-01T00:00:00.000Z E Crash: fake entry\rmore")
        val lines = allText().lines().filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("    "))
        assertTrue(lines[2].startsWith("    "))
    }

    @Test
    fun controlCharactersAreReplaced() {
        log.i("T", "bell" + Char(7) + "esc" + Char(27) + "[31mred")
        val text = allText()
        assertFalse(text.any { it < ' ' && it != '\n' })
    }

    @Test
    fun filesRollAndTheTotalStaysCapped() {
        repeat(500) { log.i("Bulk", "line $it " + "x".repeat(100)) }
        log.flush()
        val files = log.files()
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 4_096 })
        // The newest line survives, the oldest is gone.
        val text = allText()
        assertTrue("line 499 " in text)
        assertFalse("line 0 " in text)
    }

    @Test
    fun aHugeMessageIsCut() {
        log.i("Big", "y".repeat(100_000))
        assertTrue(allText().length < AppLog.MAX_LINE + 200)
    }

    @Test
    fun anUnwritableFolderNeverThrows() {
        val blocker = File(tmp.root, "blocked").apply { writeText("a file where the folder should be") }
        val broken = AppLog({ File(blocker, "logs") }, clock, echo = false)
        broken.i("T", "goes nowhere")
        broken.flush()
        broken.close()
    }

    @Test
    fun crashHandlerWritesARedactedCrashFileAndCallsThePreviousHandler() {
        var previousCalled = false
        val handler = CrashHandler(log, { File(dir, "crash") }, clock, "1.2.3") { _, _ -> previousCalled = true }
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom at https://h.example.com/f?token=SECRET"))
        assertTrue(previousCalled)
        val crash = File(dir, "crash").listFiles()!!.single()
        val text = crash.readText()
        assertTrue(text, "IllegalStateException: boom at https://h.example.com/f" in text)
        assertTrue(text, "1.2.3" in text)
        assertFalse(text, "SECRET" in text)
        assertTrue(allText().contains("Crash"))
    }

    @Test
    fun crashFilesAreCapped() {
        val handler = CrashHandler(log, { File(dir, "crash") }, clock, "1") { _, _ -> }
        repeat(CrashHandler.KEEP + 5) {
            clock.advance(1_000)
            handler.uncaughtException(Thread.currentThread(), RuntimeException("n$it"))
        }
        assertEquals(CrashHandler.KEEP, File(dir, "crash").listFiles()!!.size)
    }

    @Test
    fun exitReasonsAreLoggedOnceAndRedacted() {
        val context = appContext()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setTimestamp(5_000L)
                .setDescription("Input dispatching timed out while loading https://x.example.com/?k=SECRET")
                .build(),
        )
        val seen = ExitReasons.record(context, log, lastSeenAt = 0L)
        assertEquals(5_000L, seen)
        val text = allText()
        assertTrue(text, "ANR" in text)
        assertFalse(text, "SECRET" in text)
        val again = ExitReasons.record(context, log, lastSeenAt = seen)
        assertEquals(seen, again)
        assertEquals(1, Regex("ANR").findAll(allText()).count())
    }

    @Test
    fun anExitDescriptionIsRedactedBeforeItIsCut() {
        // CodeRabbit review (PR 3): the description was cut to 500 characters first, which could cut a URL just
        // before the '@' of its userinfo; the redaction then no longer saw userinfo and kept the password.
        val context = appContext()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_CRASH)
                .setTimestamp(6_000L)
                .setDescription("x".repeat(479) + " https://alice:SECRET@h.example/f")
                .build(),
        )
        ExitReasons.record(context, log, lastSeenAt = 0L)
        val text = allText()
        assertTrue(text, "crash" in text)
        assertFalse(text, "SECRET" in text)
        assertFalse(text, "alice" in text)
    }

    @Test
    fun crashesInTheSameSecondKeepTheNewestAndDropTheOldest() {
        // CodeRabbit review (PR 3): sorting by name put "crash-X.txt" before "crash-X-2.txt" and "-9" before
        // "-10", so retention deleted a newer report than the oldest.
        val crashDir = File(dir, "crash")
        val handler = CrashHandler(log, { crashDir }, clock, "1") { _, _ -> }
        // Two past the limit: the second rotation must not reuse the first crash's freed name for the newest one.
        repeat(CrashHandler.KEEP + 2) { handler.uncaughtException(Thread.currentThread(), RuntimeException("n$it")) }
        val left = crashDir.listFiles()!!
        assertEquals(CrashHandler.KEEP, left.size)
        val texts = left.map { it.readText() }
        assertFalse("the oldest are gone", texts.any { "RuntimeException: n0\n" in it || "RuntimeException: n1\n" in it })
        assertTrue("the newest stays", texts.any { "RuntimeException: n${CrashHandler.KEEP + 1}\n" in it })
        val newestFirst = CrashHandler.newestFirst(left.toList())
        assertTrue(newestFirst.first().readText(), "RuntimeException: n${CrashHandler.KEEP + 1}\n" in newestFirst.first().readText())
        assertTrue(newestFirst.last().readText(), "RuntimeException: n2\n" in newestFirst.last().readText())
    }

    @Test
    fun aSnapshotTakenWhileTheLogRollsHasNoGap() {
        // CodeRabbit review (PR 3): reading file by file while the log rolled could skip a whole file. A snapshot
        // is taken under the writer's lock, so the lines it holds are always one unbroken run.
        val writing = Thread { repeat(4_000) { log.logNow(AppLog.Level.Info, "T", "line $it") } }.apply { start() }
        val number = Regex("line (\\d+)$")
        while (writing.isAlive) {
            val seen = log.snapshot(1 shl 20).flatMap { it.lines() }.mapNotNull { number.find(it)?.groupValues?.get(1)?.toInt() }
            if (seen.isNotEmpty()) assertEquals(seen.toString(), (seen.first()..seen.last()).toList(), seen)
        }
    }

    @Test
    fun crashesOnTwoThreadsAtOnceEachGetTheirOwnFile() {
        // CodeRabbit review (PR 3): two threads could both find the same name free and overwrite each other.
        val crashDir = File(dir, "crash")
        val handler = CrashHandler(log, { crashDir }, clock, "1") { _, _ -> }
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..4).map { n ->
            Thread {
                start.await()
                handler.uncaughtException(Thread.currentThread(), RuntimeException("thread$n"))
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(10_000) }
        val texts = crashDir.listFiles()!!.map { it.readText() }
        assertEquals(4, texts.size)
        (1..4).forEach { n -> assertTrue(texts.any { "RuntimeException: thread$n\n" in it }) }
    }
}
