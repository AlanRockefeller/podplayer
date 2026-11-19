/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

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
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/**
 * Owner, 2026-11-18: a search result opens a preview of the show, its episodes listed, before subscribing. The feed is
 * fetched only on that tap, and a search result is never treated as an address the owner typed (SPEC §3).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ShowPreviewViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private val feedUrl = "https://found.example.com/feed.xml"

    private fun preview() = ShowPreviewViewModel(env.deps, feedUrl)

    @Test
    fun theShowAndAllItsEpisodesAreShownWithoutSubscribing() = runBlocking {
        env.answer = { success(rss("Found Show", episodes = 12).toByteArray(), "application/rss+xml") }
        val state = awaitState(preview().state) { it is ShowPreviewState.Ready } as ShowPreviewState.Ready
        assertEquals("Found Show", state.title)
        assertEquals("A Host", state.author)
        assertEquals(12, state.episodes.size)
        assertEquals("newest first, as the feed lists them", "Episode 12", state.episodes.first().title)
        assertEquals("About Found Show", state.description?.text)
        assertEquals(listOf(AddressPolicy.Default), env.policies.distinct())
        assertTrue("nothing is subscribed by a preview", env.repos.feeds.feeds().first().isEmpty())
    }

    @Test
    fun subscribingFromThePreviewIsNotOwnerTypedAndKeepsItsEpisodes() = runBlocking {
        env.answer = { success(rss("Found Show", episodes = 4).toByteArray(), "application/rss+xml") }
        val vm = preview()
        awaitState(vm.state) { it is ShowPreviewState.Ready }
        vm.subscribe()
        val feedId = awaitState(vm.opened) { it != null }!!
        val feed = env.repos.feeds.get(feedId)!!
        assertNull("a search result never approves a private host", feed.ownerApprovedHost)
        assertEquals(4, env.repos.episodes.forFeed(feedId).first().size)
        eventually { env.refreshes == listOf<Long?>(feedId) }
    }

    private suspend fun storedId(guid: String): Long =
        env.repos.feeds.allFeeds().first().single().let { feed -> env.repos.episodes.forFeed(feed.id).first().single { it.guid == guid }.id }

    @Test
    fun playingAnEpisodeKeepsTheShowAsideUnsubscribedAndPlaysIt() = runBlocking {
        env.answer = { success(rss("Found Show", episodes = 4).toByteArray(), "application/rss+xml") }
        val vm = preview()
        awaitState(vm.state) { it is ShowPreviewState.Ready }
        vm.play(1)
        eventually { env.playback.commands.isNotEmpty() }
        assertEquals(listOf("play ${storedId("g3")}"), env.playback.commands)
        val feed = env.repos.feeds.allFeeds().first().single()
        assertFalse("kept aside, not subscribed", feed.subscribed)
        assertNull("a search result never approves a private host", feed.ownerApprovedHost)
        assertTrue("not in the library", env.repos.feeds.feeds().first().isEmpty())
        assertEquals("the preview's fetch only", 1, env.requests.size)
        assertTrue("no refresh asked for", env.refreshes.isEmpty())
        assertEquals(false, (vm.state.value as ShowPreviewState.Ready).opening)
    }

    @Test
    fun tappingAnEpisodeOpensItsPage() = runBlocking {
        env.answer = { success(rss("Found Show", episodes = 4).toByteArray(), "application/rss+xml") }
        val vm = preview()
        awaitState(vm.state) { it is ShowPreviewState.Ready }
        vm.openEpisode(0)
        val opened = awaitState(vm.openedEpisode) { it != null }
        assertEquals(storedId("g4"), opened)
        assertTrue(env.playback.commands.isEmpty())
    }

    @Test
    fun subscribingAfterPlayingTurnsTheSameShowIntoASubscription() = runBlocking {
        env.answer = { success(rss("Found Show", episodes = 4).toByteArray(), "application/rss+xml") }
        val vm = preview()
        awaitState(vm.state) { it is ShowPreviewState.Ready }
        vm.play(0)
        eventually { env.playback.commands.isNotEmpty() }
        val played = storedId("g4")
        vm.subscribe()
        val feedId = awaitState(vm.opened) { it != null }!!
        assertEquals(listOf(feedId), env.repos.feeds.feeds().first().map { it.id })
        assertEquals("the same episode row, so its position is kept", feedId, env.repos.episodes.get(played)!!.feedId)
        eventually { env.refreshes == listOf<Long?>(feedId) }
    }

    @Test
    fun aShowKeptAsideEarlierStillOpensAsAPreview() = runBlocking {
        env.answer = { success(rss("Found Show", episodes = 2).toByteArray(), "application/rss+xml") }
        val first = preview()
        awaitState(first.state) { it is ShowPreviewState.Ready }
        first.play(0)
        eventually { env.playback.commands.isNotEmpty() }
        val again = preview()
        awaitState(again.state) { it is ShowPreviewState.Ready }
        assertNull(again.opened.value)
    }

    @Test
    fun anAlreadySubscribedShowOpensWithoutFetching() = runBlocking {
        val id = env.show("Found Show", url = feedUrl)
        val vm = preview()
        assertEquals(id, awaitState(vm.opened) { it != null })
        assertTrue(env.requests.isEmpty())
    }

    @Test
    fun offlineNothingIsFetchedAndItSaysSo() {
        env.online.value = false
        val state = awaitState(preview().state) { it is ShowPreviewState.Failed } as ShowPreviewState.Failed
        assertEquals(DiscoverProblem.Offline, state.problem)
        assertTrue(env.requests.isEmpty())
    }

    @Test
    fun aFeedThatWontLoadOrIsntAFeedSaysWhy() {
        env.answer = { FetchResult.Failure(NetError.Network("down")) }
        val down = awaitState(preview().state) { it is ShowPreviewState.Failed } as ShowPreviewState.Failed
        assertTrue(down.problem is DiscoverProblem.Net)

        env.answer = { success("<html>no</html>".toByteArray(), "text/html") }
        val notFeed = awaitState(preview().state) { it is ShowPreviewState.Failed } as ShowPreviewState.Failed
        assertTrue(notFeed.problem is DiscoverProblem.NotAFeed)
    }

    @Test
    fun tryAgainFetchesAgain() {
        env.answer = { FetchResult.Failure(NetError.Network("down")) }
        val vm = preview()
        awaitState(vm.state) { it is ShowPreviewState.Failed }
        env.answer = { success(rss("Found Show").toByteArray(), "application/rss+xml") }
        vm.retry()
        awaitState(vm.state) { it is ShowPreviewState.Ready }
        assertEquals(2, env.requests.size)
    }
}
