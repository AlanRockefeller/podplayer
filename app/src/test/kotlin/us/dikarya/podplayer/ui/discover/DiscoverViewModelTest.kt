/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** SPEC §12 Discover: Apple search, pasted URL with preview, OPML import with preview. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DiscoverViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private val appleAnswer = """{"results":[
        {"collectionName": "Found Show", "artistName": "Pub", "feedUrl": "https://found.example.com/feed.xml"},
        {"collectionName": "Other", "feedUrl": "https://other.example.com/feed.xml"}]}"""

    @Test
    fun searchTermsGoToAppleAndSubscribingFromResultsIsNotOwnerTyped() = runBlocking {
        env.answer = { success(appleAnswer.toByteArray(), "application/json") }
        val vm = DiscoverViewModel(env.deps)
        vm.onQueryChange("found show")
        vm.submit()
        val results = awaitState(vm.state) { it.search is SearchState.Results }
        assertEquals("itunes.apple.com", env.requests.single().host)
        val hit = (results.search as SearchState.Results).hits.first()
        assertEquals("Found Show", hit.title)

        vm.subscribe(hit)
        val subscribed = awaitState(vm.state) { hit.feedUrl in it.subscribedUrls }
        val feed = env.repos.feeds.get(subscribed.subscribedUrls.getValue(hit.feedUrl))!!
        assertNull("a search result never approves a private host", feed.ownerApprovedHost)
        // subscribedUrls follows Room's feed list, which can update before the subscribe coroutine asks for the refresh.
        eventually { env.refreshes == listOf<Long?>(feed.id) }
    }

    @Test
    fun aPastedUrlIsPreviewedThenSubscribedAsOwnerTypedAndOpened() = runBlocking {
        env.answer = { success(rss("Home Server Show", episodes = 4).toByteArray(), "application/rss+xml") }
        val vm = DiscoverViewModel(env.deps)
        vm.onQueryChange("http://192.168.1.20:8000/podcast.xml?key=abc")
        vm.submit()
        val previewed = awaitState(vm.state) { it.preview is PreviewState.Ready }
        val preview = (previewed.preview as PreviewState.Ready).preview
        assertEquals("Home Server Show", preview.title)
        assertEquals(4, preview.episodeCount)
        assertTrue("nothing is subscribed by a preview", env.repos.feeds.feeds().first().isEmpty())

        vm.subscribePreview()
        val feedId = awaitState(vm.state) { it.openShow != null }.openShow!!
        vm.onShowOpened()
        val feed = env.repos.feeds.get(feedId)!!
        assertEquals("the typed host is approved (SPEC §3)", "192.168.1.20", feed.ownerApprovedHost)
        assertEquals("the preview's episodes are there at once", 4, env.repos.episodes.forFeed(feedId).first().size)
        assertEquals(listOf<Long?>(feedId), env.refreshes)
        val after = awaitState(vm.state) { it.preview is PreviewState.None && it.openShow == null }
        assertEquals("", after.query)
    }

    @Test
    fun offlineSearchSendsNothingAndSaysSo() {
        env.online.value = false
        val vm = DiscoverViewModel(env.deps)
        awaitState(vm.state) { !it.online }
        vm.onQueryChange("anything")
        vm.submit()
        val state = awaitState(vm.state) { it.search is SearchState.Failed }
        assertEquals(DiscoverProblem.Offline, (state.search as SearchState.Failed).problem)
        assertTrue(env.requests.isEmpty())
    }

    @Test
    fun aRefusedAddressExplainsWhy() {
        val vm = DiscoverViewModel(env.deps)
        vm.onQueryChange("feed://example.com/rss")
        vm.submit()
        val state = awaitState(vm.state) { it.preview is PreviewState.Failed }
        assertTrue((state.preview as PreviewState.Failed).problem is DiscoverProblem.BadUrl)
        assertTrue(env.requests.isEmpty())
    }

    @Test
    fun opmlImportShowsAPreviewAndAddsOnlyWhenConfirmed() = runBlocking {
        env.show("Existing", url = "https://two.example.com/feed")
        val opml = """<?xml version="1.0"?><opml version="2.0"><body>
            <outline text="One" type="rss" xmlUrl="https://one.example.com/feed"/>
            <outline text="Two" type="rss" xmlUrl="https://two.example.com/feed"/>
            <outline text="Bad" type="rss" xmlUrl="javascript:x"/></body></opml>"""
        val vm = DiscoverViewModel(env.deps)
        vm.opmlPicked { ByteArrayInputStream(opml.toByteArray()) }
        val previewed = awaitState(vm.state) { it.opml is OpmlState.Ready }
        val ready = previewed.opml as OpmlState.Ready
        assertEquals(2, ready.feeds.size)
        assertEquals(1, ready.skipped)
        assertEquals("nothing added before confirming", 1, env.repos.feeds.feeds().first().size)

        vm.importOpml()
        val done = awaitState(vm.state) { it.opml is OpmlState.Done }
        assertEquals(us.dikarya.podplayer.ui.browse.ImportSummary(added = 1, alreadySubscribed = 1, refused = 0), (done.opml as OpmlState.Done).summary)
        val added = env.repos.feeds.feeds().first().single { it.url == "https://one.example.com/feed" }
        assertNull("OPML imports never approve a private host", added.ownerApprovedHost)
        eventually { env.refreshes == listOf<Long?>(null) }
    }
}
