/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.feed.FeedFormat
import us.dikarya.podplayer.core.feed.ParsedEnclosure
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.ParsedFeed
import us.dikarya.podplayer.core.jobs.refresh.androidFeedParser
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.playback.PlaybackControls
import us.dikarya.podplayer.playback.PlaybackUiState
import us.dikarya.podplayer.playback.SleepUi
import us.dikarya.podplayer.ui.discover.AppleSearch
import us.dikarya.podplayer.ui.discover.FeedPreviewer
import us.dikarya.podplayer.ui.discover.GateFetch
import us.dikarya.podplayer.ui.discover.importlist.Pacer

/**
 * The session as our screens see it, standing in for `PlaybackClient`. Commands are recorded, and [playEpisode]
 * changes [state] the way the real session reports it, so tests can watch the screens follow the session.
 */
class FakePlayback : PlaybackControls {
    override val state = MutableStateFlow(PlaybackUiState(connected = true))
    val commands = mutableListOf<String>()

    override fun connect() = Unit

    override fun playEpisode(id: Long, startAtMs: Long?) {
        commands += if (startAtMs == null) "play $id" else "play $id at $startAtMs"
        state.value = state.value.copy(episodeId = id, playWhenReady = true, isPlaying = true, positionMs = startAtMs ?: 0, title = "Episode $id")
    }

    override fun play() {
        commands += "resume"
        state.value = state.value.copy(playWhenReady = true, isPlaying = true)
    }

    override fun pause() {
        commands += "pause"
        state.value = state.value.copy(playWhenReady = false, isPlaying = false)
    }

    override fun seekTo(positionMs: Long) {
        commands += "seek $positionMs"
    }

    override fun skipBack() {
        commands += "back"
    }

    override fun skipForward() {
        commands += "forward"
    }

    override fun setSpeed(speed: Float?, forShow: Boolean) {
        commands += "speed $speed forShow=$forShow"
    }

    override fun sleepAfterMinutes(minutes: Int) {
        commands += "sleep $minutes"
        state.value = state.value.copy(sleep = SleepUi.Minutes(minutes * 60_000L))
    }

    override fun sleepAtEndOfEpisode() {
        commands += "sleep end"
        state.value = state.value.copy(sleep = SleepUi.EndOfEpisode)
    }

    override fun cancelSleepTimer() {
        commands += "sleep off"
        state.value = state.value.copy(sleep = SleepUi.Off)
    }
}

class FakeDownloads(private val repos: suspend () -> Repositories?) : DownloadActions {
    val calls = mutableListOf<String>()

    override suspend fun download(episodeId: Long) {
        calls += "download $episodeId"
    }

    override suspend fun cancel(episodeId: Long) {
        calls += "cancel $episodeId"
    }

    override suspend fun delete(episodeId: Long) {
        calls += "delete $episodeId"
    }

    override suspend fun unsubscribe(feedId: Long) {
        calls += "unsubscribe $feedId"
        repos()?.feeds?.unsubscribe(feedId)
    }
}

/** A real library in a temporary folder, with fakes for the session, downloads and the network. */
class BrowseTestEnv(root: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val log = AppLog({ File(root, "logs") }, echo = false)
    val data = DataLayer(context, scope, "test", log, filesProvider = { DataFiles.under(File(root, "app")) }).also { it.start() }
    val repos: Repositories = runBlocking { data.awaitRepositories()!! }
    val playback = FakePlayback()
    val downloads = FakeDownloads { repos }
    val refreshes = mutableListOf<Long?>()
    val online = MutableStateFlow(true)

    /** What the gate answers; every request is recorded. */
    var answer: (okhttp3.HttpUrl) -> FetchResult = { FetchResult.Failure(NetError.Network("no test server")) }
    val requests = mutableListOf<okhttp3.HttpUrl>()

    /** Each request with the list importer's (fake) time when it was made. */
    val requestTimes = mutableListOf<Pair<okhttp3.HttpUrl, Long>>()

    /** Runs before each answer; a test can suspend here to hold a request open. */
    var beforeAnswer: suspend (okhttp3.HttpUrl) -> Unit = {}
    private val gate = GateFetch { url, _, _ ->
        requests += url
        requestTimes += url to pacerNow
        beforeAnswer(url)
        answer(url)
    }

    val deps = BrowseDeps(
        repositories = { data.awaitRepositories() },
        settings = data.settings,
        playback = playback,
        downloads = downloads,
        refresh = { refreshes += it },
        online = online,
        search = AppleSearch(gate),
        previewer = FeedPreviewer(gate, androidFeedParser()),
        subscriptions = Subscriptions({ data.awaitRepositories() }, { data.settings.autoDownloadNewShows.first() }) { refreshes += it },
        log = log,
        importPacer = { Pacer(clock = { pacerNow }) { ms -> pacedWaits += ms; pacerNow += ms } },
    )

    /** The list importer's pacing, on a clock of its own so tests don't wait: every wait is recorded. */
    val pacedWaits: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf<Long>())

    @Volatile
    private var pacerNow = 0L

    /** Subscribes to a show with [episodes] episodes (newest first: "<title> 1" is the newest). */
    fun show(title: String, episodes: Int = 3, url: String = "https://${title.lowercase().replace(' ', '-')}.example.com/feed.xml", publishedBase: Long = 1_790_000_000_000L): Long = runBlocking {
        val id = (repos.feeds.subscribe(url, ownerTyped = false) as SubscribeResult.Subscribed).feedId
        val items = (1..episodes).map { n ->
            ParsedEpisode(
                guid = "$title-$n",
                title = "$title $n",
                publishedAt = publishedBase - n * DAY,
                enclosure = ParsedEnclosure("https://cdn.example.com/${id}_$n.mp3", "audio/mpeg", 1000),
                durationMs = 1_800_000,
                notes = "<p>Notes for $title $n. Chapter two at 12:34.</p>",
            )
        }
        repos.feeds.applyRefresh(id, ParsedFeed(FeedFormat.Rss, title = title, author = "Host of $title", description = "<p>About $title</p>", episodes = items))
        id
    }

    fun episodeIds(feedId: Long): List<Long> = runBlocking { repos.episodes.forFeed(feedId).first().map { it.id } }

    /**
     * Closes on another thread while the main looper keeps running: a view model's write that is still finishing
     * resumes on the main thread, and blocking that thread here would wait for it forever.
     */
    fun close() {
        val done = CountDownLatch(1)
        thread(name = "close-test-env") {
            try {
                runBlocking { data.close() }
                scope.cancel()
            } finally {
                done.countDown()
            }
        }
        while (!done.await(10, TimeUnit.MILLISECONDS)) shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        const val DAY = 24L * 3600 * 1000
    }
}

/**
 * Waits until [state] satisfies [predicate], running the main looper (where view models collect) meanwhile.
 * Room answers on its own threads, so this polls with a deadline instead of assuming one idle is enough.
 */
fun <T> awaitState(state: StateFlow<T>, timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T {
    // A screen collects the state while it is shown; view models only compute it while someone does.
    val screen = CoroutineScope(Dispatchers.Main.immediate).launch { state.collect {} }
    try {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val value = state.value
            if (predicate(value)) return value
            if (System.currentTimeMillis() > deadline) throw AssertionError("state never matched; last: $value")
            Thread.sleep(10)
        }
    } finally {
        screen.cancel()
    }
}

/** Runs the main looper until [condition] holds, for side effects that aren't in a state. */
fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) throw AssertionError("condition never held")
        Thread.sleep(10)
    }
}
