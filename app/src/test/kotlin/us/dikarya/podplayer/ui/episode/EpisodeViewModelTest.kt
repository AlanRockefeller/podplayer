/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.episode

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
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.ui.browse.BrowseTestEnv
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** SPEC §12 episode page: sanitized notes with timestamp links, play, queue, download, mark played. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EpisodeViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env by lazy { BrowseTestEnv(tmp.root) }

    @After
    fun tearDown() = env.close()

    @Test
    fun notesAreSanitizedWithTheirTimestampsAndATimestampPlaysFromThere() {
        val feed = env.show("Alpha", episodes = 1)
        val id = env.episodeIds(feed).single()
        val vm = EpisodeViewModel(env.deps, id)
        val state = awaitState(vm.state) { it.notes is NotesState.Ready }
        assertEquals("Alpha", state.item!!.showTitle)
        val notes = (state.notes as NotesState.Ready).text
        assertTrue(notes.text.contains("Chapter two at 12:34."))
        val stamp = notes.spans.single { it.style is RichStyle.Timestamp }.style as RichStyle.Timestamp
        assertEquals(754_000L, stamp.positionMs)

        vm.playAt(stamp.positionMs)
        assertEquals(listOf("play $id at 754000"), env.playback.commands)
    }

    @Test
    fun hostileNotesNeverReachTheScreen() = runBlocking {
        val feed = env.show("Alpha", episodes = 1)
        val id = env.episodeIds(feed).single()
        val parsed = us.dikarya.podplayer.core.feed.ParsedFeed(
            us.dikarya.podplayer.core.feed.FeedFormat.Rss,
            title = "Alpha",
            episodes = listOf(
                us.dikarya.podplayer.core.feed.ParsedEpisode(
                    guid = "Alpha-1",
                    title = "Alpha 1",
                    publishedAt = 1_790_000_000_000L - BrowseTestEnv.DAY,
                    enclosure = us.dikarya.podplayer.core.feed.ParsedEnclosure("https://cdn.example.com/${feed}_1.mp3", "audio/mpeg", 1000),
                    notes = """<script>steal()</script><p onclick="x()">Hi <a href="javascript:alert(1)">there</a> <img src="https://tracker.example/p.gif"></p><iframe src="https://evil.example"></iframe>""",
                ),
            ),
        )
        env.repos.feeds.applyRefresh(feed, parsed)
        val vm = EpisodeViewModel(env.deps, id)
        val notes = (awaitState(vm.state) { s -> (s.notes as? NotesState.Ready)?.text?.text?.startsWith("Hi") == true }.notes as NotesState.Ready).text
        assertEquals("Hi there", notes.text.trim())
        assertTrue(notes.spans.none { it.style is RichStyle.Link })
    }

    @Test
    fun markPlayedQueueAndDownload() = runBlocking {
        val feed = env.show("Alpha", episodes = 1)
        val id = env.episodeIds(feed).single()
        val vm = EpisodeViewModel(env.deps, id)
        val item = awaitState(vm.state) { it.item != null }.item!!
        assertFalse(item.played)

        vm.actions.setPlayed(id, true)
        awaitState(vm.state) { it.item?.played == true }
        vm.actions.setPlayed(id, false)
        awaitState(vm.state) { it.item?.played == false }

        vm.actions.toggleQueue(item)
        awaitState(vm.state) { it.item?.queued == true }
        assertEquals(listOf(id), env.repos.queue.entries().first().map { it.episodeId })

        vm.actions.download(id)
        eventually { env.downloads.calls == listOf("download $id") }

        vm.actions.togglePlay(item)
        assertEquals(listOf("play $id"), env.playback.commands)
    }

    @Test
    fun anEpisodeThatIsGoneSaysSo() {
        val vm = EpisodeViewModel(env.deps, 9_999)
        awaitState(vm.state) { it.gone }
    }
}
