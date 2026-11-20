/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover.importlist

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
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
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.discover.DiscoverProblem
import us.dikarya.podplayer.ui.discover.rss
import us.dikarya.podplayer.ui.discover.success

/**
 * SPEC §12 "Import a list of shows": lines matched through Apple's search (paced), a preview to pick, change or
 * skip each match, and subscribing only after the owner confirms, with `ownerTyped = false`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ListImportViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    /** Apple answers every search with up to three shows named after the term. */
    private fun appleAnswers(empty: Set<String> = emptySet(), status: Map<String, Int> = emptyMap()) {
        env.answer = { url ->
            when (url.host) {
                "itunes.apple.com" -> {
                    val term = url.queryParameter("term") ?: "id${url.queryParameter("id")}"
                    val code = status[term]
                    when {
                        code != null -> FetchResult.Failure(NetError.HttpStatus(code))
                        term in empty -> success("""{"results":[]}""".toByteArray(), "application/json")
                        else -> {
                            val results = (1..3).joinToString(",") { n ->
                                """{"collectionName":"$term $n","artistName":"Host","feedUrl":"https://feeds.example.com/${slug(term)}-$n.xml"}"""
                            }
                            success("""{"results":[$results]}""".toByteArray(), "application/json")
                        }
                    }
                }
                else -> success(rss("Fetched ${url.encodedPath}", episodes = 3).toByteArray(), "application/rss+xml")
            }
        }
    }

    private fun hosts(): Set<String> = env.requests.map { it.host }.toSet()

    private fun ListImportViewModel.matched() = awaitState(state) { s -> s.rows.isNotEmpty() && !s.matching }

    @Test
    fun namesAreSearchedPacedAndNothingIsSubscribedUntilConfirmed() = runBlocking {
        appleAnswers()
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Science Hour\nGardening\nThe Daily")
        vm.findShows()
        val state = vm.matched()
        assertEquals(listOf("Science Hour 1", "Gardening 1", "The Daily 1"), state.rows.map { it.chosenHit?.title })
        assertEquals("only Apple's search was asked", setOf("itunes.apple.com"), hosts())
        assertEquals(3, env.requests.size)
        assertEquals("one request every 3 s", listOf(Pacer.DEFAULT_INTERVAL_MS, Pacer.DEFAULT_INTERVAL_MS), env.pacedWaits.toList())
        assertTrue("a preview subscribes nothing", env.repos.feeds.feeds().first().isEmpty())
        assertEquals(3, state.toSubscribe)
    }

    @Test
    fun theOwnerCanPickAnotherMatchOrSkipThenConfirm() = runBlocking {
        appleAnswers()
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Science Hour\nGardening\nThe Daily")
        vm.findShows()
        val rows = vm.matched().rows
        vm.choose(rows[0].key, 2)
        vm.skip(rows[1].key)
        assertEquals(2, awaitState(vm.state) { it.toSubscribe == 2 }.toSubscribe)
        vm.confirm()
        val done = awaitState(vm.state) { it.summary != null }.summary!!
        assertEquals(2, done.added)
        assertEquals(1, done.skipped)
        val feeds = env.repos.feeds.feeds().first()
        assertEquals(
            setOf("https://feeds.example.com/science-hour-3.xml", "https://feeds.example.com/the-daily-1.xml"),
            feeds.map { it.url }.toSet(),
        )
        assertTrue("search matches are never owner-typed", feeds.all { it.ownerApprovedHost == null })
    }

    @Test
    fun feedAddressesAreNotFetchedBeforeConfirmingAndApproveNoHost() = runBlocking {
        appleAnswers()
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("http://192.168.1.20/feed.xml\nhttps://feeds.example.org/rss?token=SECRET")
        vm.findShows()
        val state = vm.matched()
        assertEquals(2, state.toSubscribe)
        assertTrue("no request at all for addresses", env.requests.isEmpty())
        assertTrue("addresses are shown redacted", state.rows.none { "SECRET" in it.label })
        vm.confirm()
        awaitState(vm.state) { it.summary != null }
        val feeds = env.repos.feeds.feeds().first()
        assertEquals(2, feeds.size)
        assertTrue(feeds.all { it.ownerApprovedHost == null })
    }

    @Test
    fun anAppleLinkIsLookedUp() = runBlocking {
        appleAnswers()
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("https://podcasts.apple.com/us/podcast/x/id42")
        vm.findShows()
        vm.matched()
        assertEquals(listOf("lookup"), env.requests.single().pathSegments)
    }

    /** When (on the importer's fake clock) each search term was asked. */
    private fun askedAt(term: String): List<Long> =
        env.requestTimes.filter { (url, _) -> url.queryParameter("term") == term }.map { it.second }

    @Test
    fun aFailedSearchIsTriedAgainAfter10_30And90SecondsThenGivesUp() = runBlocking {
        appleAnswers(empty = setOf("Nothing Like It"), status = mapOf("Busy" to 429))
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Nothing Like It\nBusy\nFine")
        vm.findShows()
        val rows = vm.matched().rows
        assertEquals(listOf(RowStatus.NoMatch, RowStatus.Failed, RowStatus.Matched), rows.map { it.status })
        val times = askedAt("Busy")
        assertEquals("the first try and three retries", 4, times.size)
        val gaps = times.zipWithNext { a, b -> b - a }
        ListImportViewModel.RETRY_WAITS_MS.zip(gaps).forEach { (wait, gap) -> assertTrue("waited $gap for $wait", gap >= wait) }
        assertNull("it gave up: no retry is pending", rows[1].retryAt)
        assertEquals("a search that wasn't throttled isn't retried", 1, askedAt("Nothing Like It").size)

        appleAnswers()
        vm.retry(rows[1].key)
        assertEquals(RowStatus.Matched, awaitState(vm.state) { s -> s.rows[1].status == RowStatus.Matched && !s.matching }.rows[1].status)
        assertEquals(2, vm.state.value.toSubscribe)
    }

    @Test
    fun aSearchThatWorksOnARetryMatchesWithoutTheOwnerAndDoesNotHoldUpTheOthers() = runBlocking {
        var busyLeft = 2
        appleAnswers()
        val apple = env.answer
        env.answer = { url ->
            if (url.queryParameter("term") == "Busy" && busyLeft-- > 0) FetchResult.Failure(NetError.HttpStatus(503)) else apple(url)
        }
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Busy\nGardening\nThe Daily")
        vm.findShows()
        val state = vm.matched()
        assertEquals(listOf(RowStatus.Matched, RowStatus.Matched, RowStatus.Matched), state.rows.map { it.status })
        assertEquals("Busy 1", state.rows[0].chosenHit?.title)
        val terms = env.requests.map { it.queryParameter("term") }
        assertEquals("the other lines went on while Busy waited", listOf("Busy", "Gardening", "The Daily", "Busy", "Busy"), terms)
    }

    @Test
    fun matchingStaysOnWhileARetryIsPending() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        appleAnswers()
        val apple = env.answer
        env.answer = { url -> if (env.requests.size == 1) FetchResult.Failure(NetError.HttpStatus(429)) else apple(url) }
        env.beforeAnswer = { if (env.requests.size == 2) gate.await() }
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Busy")
        vm.findShows()
        val waiting = awaitState(vm.state) { s -> s.rows.firstOrNull()?.status == RowStatus.Searching && env.requests.size == 2 }
        assertTrue("still matching during the retry", waiting.matching)
        gate.complete(Unit)
        assertEquals(RowStatus.Matched, vm.matched().rows.single().status)
    }

    @Test
    fun aLongListThroughAThrottlingBurstAllMatchesInBoundedTime() = runBlocking {
        appleAnswers()
        val apple = env.answer
        var throttledFrom: Long? = null
        env.answer = { url ->
            // Apple refuses everything for 2 minutes from the 20th request, like the owner's 230-show import
            // (21 lines "The search didn't work"). The retries (10 s, 30 s, 90 s) outlast such a window.
            val now = env.requestTimes.last().second
            if (env.requests.size == 20) throttledFrom = now
            val from = throttledFrom
            if (from != null && now - from < 2 * 60_000L) FetchResult.Failure(NetError.HttpStatus(429)) else apple(url)
        }
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange((1..230).joinToString("\n") { "Show $it" })
        vm.findShows()
        val state = awaitState(vm.state, timeoutMs = 60_000) { s -> s.rows.isNotEmpty() && !s.matching }
        assertEquals(230, state.rows.count { it.status == RowStatus.Matched })
        val took = env.requestTimes.last().second - env.requestTimes.first().second
        assertTrue("took $took ms of (fake) time", took < 30 * 60_000L)
    }

    @Test
    fun linesFoundOfflineAreRetriedAndGiveUpWithoutAnyRequest() = runBlocking {
        appleAnswers()
        env.online.value = false
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Science Hour\nGardening")
        vm.findShows()
        val state = vm.matched()
        assertTrue(state.rows.all { it.status == RowStatus.Failed && it.problem == DiscoverProblem.Offline && it.retryAt == null })
        assertTrue("no request while offline", env.requests.isEmpty())

        env.online.value = true
        state.rows.forEach { vm.retry(it.key) }
        assertEquals(2, awaitState(vm.state) { s -> !s.matching && s.toSubscribe == 2 }.toSubscribe)
    }

    @Test
    fun tooManyLinesAreRefusedWithoutAnyRequest() {
        appleAnswers()
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange((1..ImportLines.MAX_LINES + 1).joinToString("\n") { "Show $it" })
        vm.findShows()
        assertEquals(ListImportProblem.TooMany, awaitState(vm.state) { it.problem != null }.problem)
        assertTrue(env.requests.isEmpty())
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun textSharedIntoTheAppIsTakenOnce() {
        env.deps.sharedText.value = "Gardening"
        val vm = ListImportViewModel(env.deps)
        assertEquals("Gardening", awaitState(vm.state) { it.text.isNotEmpty() }.text)
        assertNull(env.deps.sharedText.value)
    }

    @Test
    fun anImportFileBringsShowsAndTheirStarsAndPlayedMarks() = runBlocking {
        appleAnswers()
        val file = """
            {"format":"podplayer-import","version":1,"shows":[
              {"title":"Science Hour","feedUrl":"https://feeds.example.com/science.xml","episodes":[
                {"title":"Episode 3","starred":true},
                {"title":"Episode 1","played":true},
                {"title":"Episode 99","starred":true}
              ]},
              {"title":"Gardening"}
            ]}
        """.trimIndent()
        val vm = ListImportViewModel(env.deps)
        vm.filePicked { file.byteInputStream() }
        val state = vm.matched()
        assertEquals(2, state.rows.size)
        assertEquals("only the name line was searched", 1, env.requests.size)
        vm.confirm()
        val summary = awaitState(vm.state) { it.summary != null }.summary!!
        assertEquals(2, summary.added)
        assertEquals(listOf("Episode 99"), summary.unmatchedEpisodes)

        val science = env.repos.feeds.feeds().first().single { it.url == "https://feeds.example.com/science.xml" }
        assertNull(science.ownerApprovedHost)
        val episodes = env.repos.episodes.forFeed(science.id).first().associateBy { it.title }
        assertTrue(episodes.getValue("Episode 3").starred)
        assertTrue(env.repos.playback.get(episodes.getValue("Episode 1").id)!!.played)
    }

    @Test
    fun rowsCantBeChangedWhileSubscribing() = runBlocking {
        // CodeRabbit review (PR 1): skipping a row during confirmation showed it skipped, yet it was subscribed.
        appleAnswers()
        val hold = CompletableDeferred<Unit>()
        env.beforeSubscribe = { hold.await() }
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Science Hour\nGardening")
        vm.findShows()
        val rows = vm.matched().rows
        vm.confirm()
        awaitState(vm.state) { it.subscribing }
        vm.skip(rows[1].key)
        vm.choose(rows[0].key, 2)
        hold.complete(Unit)
        val done = awaitState(vm.state) { it.summary != null }
        assertEquals(listOf(false, false), done.rows.map { it.skipped })
        assertEquals(listOf(0, 0), done.rows.map { it.chosen })
        assertEquals(2, done.summary!!.added)
    }

    @Test
    fun aNewListIsNotFilledWithTheResultsOfTheListBefore() = runBlocking {
        // CodeRabbit review (PR 1): a search still running for the first list wrote its matches into the new
        // list's row with the same key, so confirming could subscribe a show the owner never asked for.
        appleAnswers()
        val gate = CompletableDeferred<Unit>()
        env.beforeAnswer = { url -> if (url.queryParameter("term") == "Alpha") gate.await() }
        val vm = ListImportViewModel(env.deps)
        vm.onTextChange("Alpha")
        vm.findShows()
        awaitState(vm.state) { s -> s.rows.firstOrNull()?.status == RowStatus.Searching }
        vm.onTextChange("Beta")
        vm.findShows()
        gate.complete(Unit)
        val row = awaitState(vm.state) { s -> s.rows.single().label == "Beta" && s.rows.single().status == RowStatus.Matched && !s.matching }.rows.single()
        assertEquals("Beta 1", row.chosenHit?.title)
    }

    @Test
    fun aWriteThatFailsWhileImportingStopsWithASummaryOfWhatWasAdded() = runBlocking {
        // CodeRabbit review (PR 1): an exception from applying the marks escaped confirm(), so the import never
        // finished (and on a phone the uncaught exception ends the app).
        appleAnswers()
        val file = """
            {"format":"podplayer-import","version":1,"shows":[
              {"title":"Science Hour","feedUrl":"https://feeds.example.com/science.xml","episodes":[{"title":"Episode 3","starred":true}]},
              {"title":"Gardening"}
            ]}
        """.trimIndent()
        val vm = ListImportViewModel(env.deps)
        vm.filePicked { file.byteInputStream() }
        vm.matched()
        env.beforeRepositories = { throw android.database.sqlite.SQLiteFullException("database or disk is full") }
        vm.confirm()
        val done = awaitState(vm.state) { it.summary != null }
        assertTrue(done.summary!!.stoppedByError)
        assertEquals(1, done.summary!!.added)
        assertTrue(!done.subscribing)
        assertEquals(listOf("https://feeds.example.com/science.xml"), env.repos.feeds.feeds().first().map { it.url })
    }

    @Test
    fun aRefusedImportFileSaysWhy() {
        val vm = ListImportViewModel(env.deps)
        vm.filePicked { """{"format":"podplayer-import","version":9,"shows":[]}""".byteInputStream() }
        assertEquals(ListImportProblem.NewerFile, awaitState(vm.state) { it.problem != null }.problem)
    }
}
