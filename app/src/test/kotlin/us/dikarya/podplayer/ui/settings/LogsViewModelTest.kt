/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.model.PositionCause
import us.dikarya.podplayer.core.model.PositionChange
import us.dikarya.podplayer.ui.browse.awaitState

/** SPEC §12/§13 Logs: each log in the viewer; the export written only when Export is tapped, redacted. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LogsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { SettingsTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun seed(): Pair<Long, Long> = runBlocking {
        val feed = env.browse.show("Alpha", episodes = 1, url = "https://alpha.example.com/feed.xml?token=FEEDSECRET")
        val episode = env.browse.episodeIds(feed).single()
        val log = env.browse.log
        log.i("Refresh", "Fetched https://alpha.example.com/feed.xml?token=APPSECRET")
        log.i("Playback", "Ignored Play from com.example.car: Bluetooth audio connected 2 s ago")
        log.i("Playback", "File failed; back to the stream at 12:00")
        env.browse.repos.logs.addRefresh(feed, RefreshOutcome.Failed, "The server had a problem (HTTP 500).")
        env.browse.repos.logs.addDownload(episode, DownloadEvent.Completed, null)
        env.browse.repos.logs.addPosition(PositionChange(episodeId = episode, at = 0, fromMs = 0, toMs = 61_000, cause = PositionCause.Restore, streaming = true))
        env.crashDir.mkdirs()
        File(env.crashDir, "crash-20261112-080000.txt").writeText("Podplayer crashed\nat https://cdn.example.com/x.mp3?sig=CRASHSECRET")
        feed to episode
    }

    @Test
    fun theAppLogTabShowsTheAppLog() {
        seed()
        val vm = LogsViewModel(env.deps)
        val state = awaitState(vm.state) { s -> s.tab == LogTab.App && s.entries.any { "Ignored Play" in it.text } }
        assertTrue(state.entries.any { "https://alpha.example.com/feed.xml" in it.text })
        assertFalse(state.entries.any { "APPSECRET" in it.text })
    }

    @Test
    fun thePlaybackTabShowsIgnoredPlaysAndFallbacksWithThePositionLog() {
        seed()
        val vm = LogsViewModel(env.deps)
        vm.select(LogTab.Playback)
        val state = awaitState(vm.state) { s -> s.tab == LogTab.Playback && s.entries.any { it.cause == PositionCause.Restore } }
        assertTrue(state.entries.any { "Ignored Play from com.example.car" in it.text })
        assertTrue(state.entries.any { "back to the stream" in it.text })
        assertFalse("only playback lines", state.entries.any { "Fetched" in it.text })
        val restore = state.entries.single { it.cause == PositionCause.Restore }
        assertEquals("Alpha 1", restore.subject)
    }

    @Test
    fun theLogsShowOnceTheLibraryOpensAfterStartFresh() {
        // Found while fixing the CodeRabbit review (PR 1): the library was looked up once, so after Start fresh the
        // download and refresh tabs stayed empty until the app was restarted.
        seed()
        env.browse.libraryOpen.value = false
        val vm = LogsViewModel(env.deps)
        vm.select(LogTab.Downloads)
        awaitState(vm.state) { s -> s.tab == LogTab.Downloads }
        env.browse.libraryOpen.value = true
        val downloads = awaitState(vm.state) { s -> s.tab == LogTab.Downloads && s.entries.isNotEmpty() }
        assertEquals("Alpha 1", downloads.entries.single().subject)
    }

    @Test
    fun theDownloadRefreshAndCrashTabsShowTheirLogs() {
        seed()
        val vm = LogsViewModel(env.deps)
        vm.select(LogTab.Downloads)
        val downloads = awaitState(vm.state) { s -> s.tab == LogTab.Downloads && s.entries.isNotEmpty() }
        assertEquals("Alpha 1", downloads.entries.single().subject)
        assertEquals(DownloadEvent.Completed, downloads.entries.single().downloadEvent)

        vm.select(LogTab.Refresh)
        val refresh = awaitState(vm.state) { s -> s.tab == LogTab.Refresh && s.entries.isNotEmpty() }
        assertEquals("Alpha", refresh.entries.single().subject)
        assertEquals(RefreshOutcome.Failed, refresh.entries.single().refreshOutcome)
        assertTrue("HTTP 500" in refresh.entries.single().text)

        vm.select(LogTab.Crashes)
        val crashes = awaitState(vm.state) { s -> s.tab == LogTab.Crashes && s.entries.isNotEmpty() }
        assertEquals("crash-20261112-080000.txt", crashes.entries.single().subject)
        assertFalse("CRASHSECRET" in crashes.entries.single().text)
    }

    @Test
    fun nothingIsWrittenForExportUntilExportIsTappedAndThenEveryUrlIsRedacted() {
        seed()
        val vm = LogsViewModel(env.deps)
        for (tab in LogTab.entries) {
            vm.select(tab)
            awaitState(vm.state) { it.tab == tab && !it.loading }
        }
        assertTrue("viewing logs writes no export", env.exportDir.listFiles().isNullOrEmpty())
        assertEquals(ExportState.Idle, vm.state.value.export)

        vm.export()
        val ready = awaitState(vm.state) { it.export is ExportState.Ready }.export as ExportState.Ready
        assertEquals(env.exportDir, ready.file.parentFile)
        val text = ready.file.readText()
        for (secret in listOf("FEEDSECRET", "APPSECRET", "CRASHSECRET", "token=", "sig=")) {
            assertFalse("$secret leaked", secret in text)
        }
        assertTrue("Ignored Play from com.example.car" in text)
        assertTrue("https://alpha.example.com/feed.xml" in text)

        vm.exportShared()
        assertEquals(ExportState.Idle, vm.state.value.export)
        // A second export replaces the first file rather than piling up copies.
        vm.export()
        awaitState(vm.state) { it.export is ExportState.Ready }
        assertEquals(1, env.exportDir.listFiles()!!.size)
    }
}
