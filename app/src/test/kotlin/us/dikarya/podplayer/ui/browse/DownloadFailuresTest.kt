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
}
