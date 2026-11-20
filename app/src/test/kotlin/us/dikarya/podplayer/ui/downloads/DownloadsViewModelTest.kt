/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.downloads

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** Library › Downloads (owner, 2026-11-18): what plays with no network, and the usual row actions on it. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DownloadsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun file(id: Long, state: DownloadState, bytes: Long, completedAt: Long?) = runBlocking {
        env.repos.media.upsert(MediaFile(id, "media/$id.mp3", state, bytes, bytes, null, completedAt))
    }

    @Test
    fun withNothingDownloadedTheListIsEmpty() {
        env.show("Alpha")
        val state = awaitState(DownloadsViewModel(env.deps).state) { !it.loading }
        assertTrue(state.empty)
    }

    @Test
    fun offlineTheDownloadsAreListedWithWhatTheyTakeAndPlay() {
        val alpha = env.episodeIds(env.show("Alpha"))
        val beta = env.episodeIds(env.show("Beta"))
        file(alpha[0], DownloadState.Complete, 30_000_000, completedAt = 100)
        file(beta[1], DownloadState.Complete, 20_000_000, completedAt = 200)
        file(beta[0], DownloadState.Downloading, 5_000_000, completedAt = null)
        file(alpha[1], DownloadState.Failed, 0, completedAt = null)
        env.online.value = false

        val vm = DownloadsViewModel(env.deps)
        val state = awaitState(vm.state) { !it.loading && it.items.size == 3 && !it.online }
        assertEquals(listOf(beta[0], beta[1], alpha[0]), state.items.map { it.id })
        assertEquals(listOf("Beta", "Beta", "Alpha"), state.items.map { it.showTitle })
        assertEquals("only finished downloads count as ready", 2, state.readyCount)
        assertEquals(50_000_000L, state.readyBytes)
        assertTrue(state.items[1].downloaded)
        assertFalse(state.items[0].downloaded)

        vm.actions.togglePlay(state.items[1])
        assertEquals("play ${beta[1]}", env.playback.commands.last())
    }

    @Test
    fun deletingADownloadFromTheListDeletesIt() {
        val alpha = env.episodeIds(env.show("Alpha"))
        file(alpha[0], DownloadState.Complete, 1_000, completedAt = 100)
        val vm = DownloadsViewModel(env.deps)
        awaitState(vm.state) { it.items.size == 1 }
        vm.actions.deleteDownload(alpha[0])
        eventually { "delete ${alpha[0]}" in env.downloads.calls }
    }
}
