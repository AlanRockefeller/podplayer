/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.db.DownloadEvent
import java.util.Collections

/**
 * The owner's phone, 2026-11-18: a download the address policy refused just went back to its Download button; the
 * reason was only in Settings › Logs. Failures from now on reach the screen, once each, with the episode's title.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DownloadFailuresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    @Test
    fun onlyNewFailuresAreReportedOnceEachWithTheEpisodeTitle() {
        val feed = env.show("Alpha", episodes = 2)
        val (one, two) = env.episodeIds(feed)
        val logs = env.repos.logs
        runBlocking { logs.addDownload(one, DownloadEvent.Failed, "an old failure") }
        Thread.sleep(5)
        val since = System.currentTimeMillis()
        val got = Collections.synchronizedList(mutableListOf<DownloadFailure>())
        scope.launch { downloadFailures(env.deps, since).collect { got += it } }

        runBlocking {
            logs.addDownload(two, DownloadEvent.Queued, null)
            logs.addDownload(two, DownloadEvent.Started, null)
            logs.addDownload(two, DownloadEvent.Failed, "192.168.1.1 is on a private network address.")
            logs.addDownload(one, DownloadEvent.Completed, "done")
        }
        eventually { got.isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf(DownloadFailure("Alpha 2", "192.168.1.1 is on a private network address.")), got.toList())
    }

    @Test
    fun aBurstLargerThanOneReadIsReportedInFullOldestFirst() {
        // CodeRabbit review (PR 1): only the newest 50 log rows were read, so the oldest of a larger burst was lost.
        val feed = env.show("Alpha", episodes = 1)
        val episode = env.episodeIds(feed).single()
        val since = System.currentTimeMillis()
        runBlocking { repeat(120) { logs().addDownload(episode, DownloadEvent.Failed, "failure $it") } }
        val got = Collections.synchronizedList(mutableListOf<DownloadFailure>())
        scope.launch { downloadFailures(env.deps, since).collect { got += it } }

        eventually { got.size >= 120 }
        Thread.sleep(300)
        assertEquals((0 until 120).map { "failure $it" }, got.map { it.reason })
    }

    @Test
    fun failuresAreReportedAgainOnceTheLibraryOpensAfterStartFresh() {
        // CodeRabbit review (PR 1): with the library closed the reader ended for good, so after Start fresh no
        // download failure reached the screen until the app was restarted.
        val feed = env.show("Alpha", episodes = 1)
        val episode = env.episodeIds(feed).single()
        env.libraryOpen.value = false
        val since = System.currentTimeMillis()
        val got = Collections.synchronizedList(mutableListOf<DownloadFailure>())
        scope.launch { downloadFailures(env.deps, since).collect { got += it } }
        Thread.sleep(200)

        env.libraryOpen.value = true
        runBlocking { logs().addDownload(episode, DownloadEvent.Failed, "refused") }
        eventually { got.isNotEmpty() }
        assertEquals(listOf(DownloadFailure("Alpha 1", "refused")), got.toList())
    }

    @Test
    fun aScreenShowsTheLibraryOnceItOpensAfterStartFresh() {
        // CodeRabbit review (PR 1): withRepositories looked the library up once, so a screen stayed on
        // "library closed" after Start fresh opened an empty library.
        env.libraryOpen.value = false
        val seen = Collections.synchronizedList(mutableListOf<String>())
        scope.launch { env.deps.withRepositories("closed") { repos -> repos.feeds.feeds().map { "open ${it.size}" } }.collect { seen += it } }
        eventually { seen.isNotEmpty() }
        env.libraryOpen.value = true
        eventually { seen.lastOrNull() == "open 0" }
        assertEquals("closed", seen.first())
    }

    private fun logs() = env.repos.logs
}
