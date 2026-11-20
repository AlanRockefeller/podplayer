/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.show

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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
import us.dikarya.podplayer.core.data.repo.KeepAsideResult
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.discover.PreviewOutcome
import us.dikarya.podplayer.ui.discover.rss
import us.dikarya.podplayer.ui.discover.success
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** SPEC §12 show page: episodes, sort, per-show speed. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ShowViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    @Test
    fun episodesAreNewestFirstWithPlayedProgressAndDownloadsAndCanBeReversed() = runBlocking {
        val feed = env.show("Alpha", episodes = 3)
        val (newest, middle, oldest) = env.episodeIds(feed)
        env.repos.playback.setPlayed(oldest, true)
        env.repos.playback.savePosition(middle, 900_000, 1_800_000)
        val vm = ShowViewModel(env.deps, feed)
        val state = awaitState(vm.state) { it.episodes.size == 3 && it.feed != null }
        assertEquals("Alpha", state.feed!!.title)
        assertEquals(listOf(newest, middle, oldest), state.episodes.map { it.id })
        assertTrue(state.episodes[2].played)
        assertEquals(0.5f, state.episodes[1].progress!!, 0.01f)
        assertEquals("About Alpha", state.description?.text)

        vm.toggleSort()
        val reversed = awaitState(vm.state) { s -> s.episodes.map { it.id } == listOf(oldest, middle, newest) }
        assertTrue(!reversed.newestFirst)
    }

    @Test
    fun aShowsOwnSpeedIsStoredForItWhenItIsNotPlaying() = runBlocking {
        val feed = env.show("Alpha")
        val vm = ShowViewModel(env.deps, feed)
        awaitState(vm.state) { it.feed != null }
        vm.setShowSpeed(1.5f)
        awaitState(vm.state) { it.showSpeed == 1.5f }
        assertEquals(1.5f, env.repos.feedSettings.speed(feed).first())
        vm.setShowSpeed(null)
        awaitState(vm.state) { it.showSpeed == null }
        assertTrue("nothing was sent to the session", env.playback.commands.isEmpty())
    }

    @Test
    fun downloadNewEpisodesIsOffAndTurnsOnFromNow() = runBlocking {
        val feed = env.show("Alpha")
        val vm = ShowViewModel(env.deps, feed)
        assertEquals(false, awaitState(vm.state) { it.feed != null }.autoDownload)
        vm.setAutoDownload(true)
        awaitState(vm.state) { it.autoDownload }
        assertTrue(env.repos.feedSettings.autoDownloadSince(feed) != null)
        vm.setAutoDownload(false)
        awaitState(vm.state) { !it.autoDownload }
        assertEquals(null, env.repos.feedSettings.autoDownloadSince(feed))
    }

    @Test
    fun aNewShowGetsTheOwnersDefault() = runBlocking {
        env.data.settings.setAutoDownloadNewShows(true)
        val on = env.deps.subscriptions.subscribe("https://on.example.com/feed.xml", ownerTyped = false)
        val id = (on as us.dikarya.podplayer.ui.browse.SubscribeOutcome.Subscribed).feedId
        assertEquals(true, env.repos.feedSettings.autoDownload(id).first())
        env.data.settings.setAutoDownloadNewShows(false)
        val off = env.deps.subscriptions.subscribe("https://off.example.com/feed.xml", ownerTyped = false)
        assertEquals(false, env.repos.feedSettings.autoDownload((off as us.dikarya.podplayer.ui.browse.SubscribeOutcome.Subscribed).feedId).first())
    }

    @Test
    fun whileTheShowPlaysItsSpeedGoesThroughTheSession() {
        val feed = env.show("Alpha")
        val playing = env.episodeIds(feed).first()
        val vm = ShowViewModel(env.deps, feed)
        awaitState(vm.state) { it.episodes.isNotEmpty() }
        env.playback.playEpisode(playing)
        awaitState(vm.state) { s -> s.episodes.any { it.loaded } }
        vm.setShowSpeed(2.0f)
        assertEquals("the session applies and stores it (CLAUDE.md: UI sends commands)", "speed 2.0 forShow=true feed=$feed", env.playback.commands.last())
    }

    @Test
    fun refreshAndUnsubscribe() = runBlocking {
        val feed = env.show("Alpha")
        val vm = ShowViewModel(env.deps, feed)
        awaitState(vm.state) { it.feed != null }
        vm.refresh()
        assertEquals(listOf<Long?>(feed), env.refreshes)
        vm.unsubscribe()
        awaitState(vm.state) { it.gone }
        // The fake records the call before Room's delete commits, so wait for the library itself, not the call.
        eventually { runBlocking { env.repos.feeds.get(feed) } == null }
        assertEquals(listOf("unsubscribe $feed"), env.downloads.calls)
    }

    @Test
    fun aFailedUnsubscribeKeepsThePageAndCanBeTriedAgain() = runBlocking {
        val feed = env.show("Alpha")
        val vm = ShowViewModel(env.deps, feed)
        awaitState(vm.state) { it.feed != null }
        env.downloads.unsubscribeFailure = java.io.IOException("disk full")
        vm.unsubscribe()
        val first = awaitState(vm.unsubscribeFailed) { it != null }!!
        assertFalse("the page stays: the show is still subscribed", vm.state.value.gone)
        assertTrue(env.repos.feeds.get(feed) != null)

        // Try again fails too: a new failure, so the screen reports it again even if it never saw the retry start.
        env.downloads.unsubscribeFailure = java.io.IOException("still full")
        vm.unsubscribe()
        val second = awaitState(vm.unsubscribeFailed) { it != null && it != first }!!
        vm.unsubscribeFailureShown(first)
        assertEquals("an older failure's dismissal leaves the newer one", second, vm.unsubscribeFailed.value)

        vm.unsubscribe()
        awaitState(vm.state) { it.gone }
        eventually { runBlocking { env.repos.feeds.get(feed) } == null }
        assertEquals("a success is no failure", null, vm.unsubscribeFailed.value)
    }

    @Test
    fun aShowKeptAsideFromAPreviewCanBeSubscribedFromItsPage() = runBlocking {
        val url = "https://aside.example.com/feed.xml"
        env.answer = { success(rss("Aside", episodes = 2).toByteArray(), "application/rss+xml") }
        val parsed = env.deps.previewer.preview(url, ownerTyped = false) as PreviewOutcome.Ready
        val feed = (env.repos.feeds.keepAside(url, parsed.preview.feed) as KeepAsideResult.Stored).feedId
        env.refreshes.clear()
        val vm = ShowViewModel(env.deps, feed)
        assertEquals(false, awaitState(vm.state) { it.feed != null }.feed!!.subscribed)
        assertEquals(2, vm.state.value.episodes.size)
        vm.subscribe()
        assertEquals(true, awaitState(vm.state) { it.feed?.subscribed == true }.feed!!.subscribed)
        assertEquals(listOf(feed), env.repos.feeds.feeds().first().map { it.id })
        eventually { env.refreshes == listOf<Long?>(feed) }
    }

    @Test
    fun anUnknownShowIsGone() {
        val vm = ShowViewModel(env.deps, 4_242)
        awaitState(vm.state) { it.gone }
    }
}
