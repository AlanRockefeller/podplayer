/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.jobs.Item
import us.dikarya.podplayer.core.jobs.TestEnv
import us.dikarya.podplayer.core.jobs.feedXml
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.xmlResponse
import us.dikarya.podplayer.core.model.DownloadState

/** Asking for a download that is already running answers at once; it doesn't wait for the transfer to end. */
@RunWith(AndroidJUnit4::class)
class DownloadRunnerQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var env: TestEnv? = null

    @After
    fun tearDown() {
        env?.close()
    }

    @Test
    fun queuingAnEpisodeThatIsDownloadingDoesNotWaitForItsTransfer() {
        val env = TestEnv(tmp.root).also { env = it }
        val feedId = env.subscribe("/feed.xml")
        env.routes["/feed.xml"] = { xmlResponse(feedXml(listOf(Item("g-1", "Episode", env.url("/ep.mp3").toString())))) }
        runBlocking { env.refresher.refresh(RefreshRequest.Feed(feedId)) }
        val episode = runBlocking { env.repos().episodes.forFeed(feedId).first().single().id }
        runBlocking {
            env.runner.queue(episode)
            val record = env.repos().media.get(episode)!!
            env.repos().media.upsert(record.copy(state = DownloadState.Downloading))
            val transferring = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            // The running transfer holds the episode's lock until it ends.
            val transfer = launch(Dispatchers.Default) {
                env.locks.withLock(episode) {
                    transferring.complete(Unit)
                    finish.await()
                }
            }
            transferring.await()
            val queued = withTimeoutOrNull(3_000) { env.runner.queue(episode) }
            finish.complete(Unit)
            transfer.join()
            assertEquals("answered while the transfer runs", true, queued)
            assertEquals(DownloadState.Downloading, env.repos().media.get(episode)!!.state)
        }
    }
}
