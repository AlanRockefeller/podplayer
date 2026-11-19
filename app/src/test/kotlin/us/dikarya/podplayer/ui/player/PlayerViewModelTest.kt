/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.player

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.playback.SleepUi
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** SPEC §12 player: everything from the session; every control is a command to it. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun playing(): Pair<Long, PlayerViewModel> {
        val feed = env.show("Alpha", episodes = 2)
        val episode = env.episodeIds(feed).first()
        val vm = PlayerViewModel(env.deps)
        env.playback.playEpisode(episode)
        awaitState(vm.state) { it.episodeId == episode && it.item != null }
        return episode to vm
    }

    @Test
    fun withNothingLoadedThePlayerSaysSo() {
        env.show("Alpha")
        val vm = PlayerViewModel(env.deps)
        val state = awaitState(vm.state) { !it.loading }
        assertNull(state.episodeId)
    }

    @Test
    fun theScreenShowsTheSessionsEpisodePositionAndLength() {
        val (episode, vm) = playing()
        env.playback.state.value = env.playback.state.value.copy(positionMs = 600_000, durationMs = 1_800_000, showTitle = "Alpha")
        val state = awaitState(vm.state) { it.positionMs == 600_000L }
        assertEquals(episode, state.episodeId)
        assertEquals("Episode $episode", state.title)
        assertEquals("Alpha", state.showTitle)
        assertEquals(1_800_000L, state.durationMs)
        assertEquals(1_200_000L, state.remainingMs)
        assertTrue(state.playing)
    }

    @Test
    fun theSeekBarShowsWhatTheSessionHasBufferedOrAllOfADownload() = runBlocking {
        val (episode, vm) = playing()
        env.playback.state.value = env.playback.state.value.copy(positionMs = 300_000, bufferedPositionMs = 900_000, durationMs = 1_800_000)
        val streaming = awaitState(vm.state) { it.bufferedMs == 900_000L }
        assertEquals(0.5f, streaming.availableFraction!!, 0.0001f)

        env.repos.media.upsert(MediaFile(episode, "media/$episode.mp3", DownloadState.Complete, 1_000, 1_000, null, 1L))
        val downloaded = awaitState(vm.state) { it.item?.downloaded == true }
        assertEquals(1f, downloaded.availableFraction!!, 0.0001f)
    }

    @Test
    fun whileTheSessionDoesNotKnowTheLengthTheFeedsIsUsed() {
        val (_, vm) = playing()
        env.playback.state.value = env.playback.state.value.copy(durationMs = null)
        val state = awaitState(vm.state) { it.durationMs != null }
        assertEquals("the feed says 30 minutes", 1_800_000L, state.durationMs)
    }

    @Test
    fun controlsAreCommandsToTheSession() {
        val (_, vm) = playing()
        env.playback.state.value = env.playback.state.value.copy(durationMs = 1_800_000)
        awaitState(vm.state) { it.durationMs == 1_800_000L }
        env.playback.commands.clear()
        vm.togglePlay()
        vm.skipBack()
        vm.skipForward()
        vm.seekTo(-5)
        vm.seekTo(9_999_999)
        vm.seekTo(42_000)
        assertEquals(listOf("pause", "back", "forward", "seek 0", "seek 1800000", "seek 42000"), env.playback.commands)
        awaitState(vm.state) { !it.playing }
        vm.togglePlay()
        assertEquals("resume", env.playback.commands.last())
    }

    @Test
    fun speedGoesToTheSessionForEveryShowOrForThisOne() = runBlocking {
        val (episode, vm) = playing()
        vm.setSpeed(1.5f, forShow = false)
        vm.setSpeed(2.0f, forShow = true)
        vm.useDefaultSpeedForShow()
        assertEquals(listOf("speed 1.5 forShow=false", "speed 2.0 forShow=true", "speed null forShow=true"), env.playback.commands.takeLast(3))

        // The show's own speed, as stored (the session stores it in the real app).
        val feedId = env.repos.episodes.get(episode)!!.feedId
        env.repos.feedSettings.setSpeed(feedId, 1.75f)
        env.data.settings.setPlaybackSpeed(1.2f)
        val state = awaitState(vm.state) { it.showSpeed == 1.75f && it.globalSpeed == 1.2f }
        assertEquals(1.75f, state.showSpeed)
    }

    @Test
    fun theSleepTimerIsSetAndShownFromTheSession() {
        val (_, vm) = playing()
        vm.sleepAfter(30)
        assertEquals(SleepUi.Minutes(1_800_000), awaitState(vm.state) { it.sleep is SleepUi.Minutes }.sleep)
        vm.sleepAtEndOfEpisode()
        awaitState(vm.state) { it.sleep == SleepUi.EndOfEpisode }
        vm.cancelSleep()
        awaitState(vm.state) { it.sleep == SleepUi.Off }
        assertEquals(listOf("sleep 30", "sleep end", "sleep off"), env.playback.commands.takeLast(3))
    }

    @Test
    fun queueAndDownloadWorkFromThePlayer() = runBlocking {
        val (episode, vm) = playing()
        val before = vm.state.value.item!!
        assertFalse(before.queued)
        vm.actions.toggleQueue(before)
        awaitState(vm.state) { it.item?.queued == true }
        assertEquals(listOf(episode), env.repos.queue.entries().first().map { it.episodeId })
        vm.actions.download(episode)
        eventually { "download $episode" in env.downloads.calls }
    }
}
