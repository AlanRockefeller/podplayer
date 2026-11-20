/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PositionCause

/**
 * Review PR #5: what happens when something else arrives while the engine waits for the store. The real store
 * suspends (Room, DataStore); the fake's hooks hold it open so a Pause, an episode ending or a controller can come
 * in at that moment.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineOrderingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val harnesses = mutableListOf<Harness>()

    private fun harness(store: FakePlaybackStore = FakePlaybackStore()): Harness = Harness(store).also { harnesses += it }

    @After
    fun tearDown() {
        harnesses.forEach { runCatching { it.release() } }
    }

    private fun Harness.playUntil(positionMs: Long) {
        run(player).untilPositionAtLeast(positionMs)
        idle()
    }

    @Test
    fun aPauseWhileAPlayIsStillLoadingTheEpisodeWins() {
        val h = harness().apply { store.add(1) }.start()
        h.store.current = 1 // nothing loaded yet: Play loads it first
        val lookup = CompletableDeferred<Unit>()
        h.store.beforeEpisode = { lookup.await() }
        h.engine.sessionPlayer.play()
        idle()
        h.engine.sessionPlayer.pause()
        idle()
        lookup.complete(Unit)
        idle()
        assertEquals("1", h.player.currentMediaItem?.mediaId)
        assertFalse("the Pause came after the Play", h.player.playWhenReady)
    }

    @Test
    fun aPauseDuringTheLoadsOwnLookupsStillWins() {
        val h = harness().apply { store.add(1) }.start()
        h.store.current = 1
        val lookup = CompletableDeferred<Unit>()
        var calls = 0
        // The first lookup chooses the episode; the second is load()'s own.
        h.store.beforeEpisode = { if (it == 1L && ++calls == 2) lookup.await() }
        h.engine.sessionPlayer.play()
        idle()
        h.engine.sessionPlayer.pause()
        idle()
        lookup.complete(Unit)
        idle()
        assertEquals("1", h.player.currentMediaItem?.mediaId)
        assertFalse(h.player.playWhenReady)
    }

    @Test
    fun aPlayWhileTheEpisodeLoadsStillPlaysWithoutAPause() {
        val h = harness().apply { store.add(1) }.start()
        h.store.current = 1
        val lookup = CompletableDeferred<Unit>()
        h.store.beforeEpisode = { lookup.await() }
        h.engine.sessionPlayer.play()
        idle()
        lookup.complete(Unit)
        idle()
        assertTrue(h.player.playWhenReady)
    }

    @Test
    fun aPauseWhileTheNextEpisodeIsBeingRecordedNeverSavesTheOldPositionUnderTheNewId() {
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.playUntil(20_000)
        val write = CompletableDeferred<Unit>()
        h.store.beforeSetCurrent = { if (it == 2L) write.await() }
        h.engine.playEpisode(2)
        idle()
        h.engine.sessionPlayer.pause()
        idle()
        write.complete(Unit)
        idle()
        assertTrue("saves for 2: ${h.store.saves}", h.store.saves.none { it.first == 2L && it.second >= 20_000 })
        assertTrue(h.store.positions[1]!! >= 20_000)
        assertEquals(2L, h.store.current)
    }

    @Test
    fun theNextEpisodeKeepsItsSavedPositionWhenTheQueueChangesAsTheEpisodeEnds() {
        val h = harness().apply {
            store.add(1, durationMs = 20_000); store.add(2); store.add(3)
            store.positions[2] = 34_000
            store.queue.value = listOf(1, 2)
        }.start()
        h.engine.playEpisode(1)
        idle()
        val lookup = CompletableDeferred<Unit>()
        h.store.beforeEpisode = { if (it == 3L) lookup.await() }
        h.store.queue.value = listOf(1, 3, 2)
        idle()
        // Episode 1 ends while the lookup for 3 is still running: the player moves on to 2, which it holds.
        run(h.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        idle()
        assertEquals("2", h.player.currentMediaItem?.mediaId)
        assertTrue("2 starts where it was left, at ${h.player.currentPosition}", h.player.currentPosition >= 34_000)
        lookup.complete(Unit)
        idle()
        assertEquals("2", h.player.currentMediaItem?.mediaId)
    }

    @Test
    fun aFailedFileFallsBackToTheStreamOnlyIfItIsStillTheEpisodePlaying() {
        val store = FakePlaybackStore()
        val h = harness(store)
        store.add(1, file = File(tmp.root, "1.mp3").apply { writeText("audio") })
        store.add(2)
        store.queue.value = listOf(1, 2)
        h.sources.brokenFiles += 1
        val lookup = CompletableDeferred<Unit>()
        store.beforeEpisode = { if (it == 1L && h.player.playerError != null) lookup.await() }
        h.start()
        h.engine.playEpisode(1)
        runMainLooperUntil { h.player.playerError != null }
        idle()
        // A controller picks episode 2 while the engine looks up 1 for its stream.
        h.player.setMediaItems(listOf(store.episodes[2]!!.toMediaItem(useFile = false)), 0, 0)
        h.player.prepare()
        idle()
        lookup.complete(Unit)
        idle()
        assertEquals("2", h.player.currentMediaItem?.mediaId)
        assertEquals(listOf("2"), (0 until h.player.mediaItemCount).map { h.player.getMediaItemAt(it).mediaId }.take(1))
    }

    @Test
    fun aShowSpeedLookedUpForTheOldEpisodeIsNotAppliedToTheNewOne() {
        val h = harness().apply {
            store.add(1, feedId = 7); store.add(2, feedId = 8)
            store.showSpeeds[7] = 1.8f
            store.showSpeeds[8] = 1.2f
            store.queue.value = listOf(1, 2)
        }.start()
        h.engine.playEpisode(1)
        idle()
        assertEquals(1.8f, h.player.playbackParameters.speed)
        val lookup = CompletableDeferred<Unit>()
        h.store.beforeShowSpeed = { if (it == 7L) lookup.await() }
        // A settings change looks up the show's speed again; before it answers, episode 2 (another show) loads.
        h.store.settings.value = h.store.settings.value.copy(skipBackSeconds = 10)
        idle()
        h.engine.playEpisode(2)
        idle()
        assertEquals(1.2f, h.player.playbackParameters.speed)
        lookup.complete(Unit)
        idle()
        assertEquals("the old show's speed stays with the old show", 1.2f, h.player.playbackParameters.speed)
    }

    @Test
    fun resumptionUsesTheQueueHeadWhenTheStoredEpisodeLeftTheLibrary() {
        val h = harness().apply { store.add(1); store.queue.value = listOf(1) }
        h.store.current = 99 // its show was unsubscribed while the service wasn't running
        val items = runBlocking { h.engine.resumptionItems() }
        assertNotNull(items)
        assertEquals("1", items!!.mediaItems.first().mediaId)
    }

    @Test
    fun aControllerMovingToTheNextItemSavesWhereTheEpisodeItLeftWas() {
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        val leftAt = h.player.currentPosition
        h.engine.sessionPlayer.seekTo(1, 0)
        idle()
        assertEquals("2", h.player.currentMediaItem?.mediaId)
        assertTrue("saved ${h.store.positions[1]}, left at $leftAt", (h.store.positions[1] ?: 0) >= leftAt)
    }

    @Test
    fun theSaveForAnEpisodeAControllerLeftSurvivesTheServiceEndingRightAfter() {
        val store = FakePlaybackStore().apply { add(1); add(2); queue.value = listOf(1, 2) }
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val h = Harness(store, persistScope = process).also { harnesses += it }.start()
            h.engine.playEpisode(1)
            h.playUntil(5_000)
            val leftAt = h.player.currentPosition
            val writes = CompletableDeferred<Unit>()
            store.beforeSave = { id, _ -> if (id == 1L) writes.await() }
            h.engine.sessionPlayer.seekTo(1, 0)
            idle()
            // The service ends while that write is still landing.
            h.release()
            writes.complete(Unit)
            idle()
            assertTrue("saved ${store.positions[1]}, left at $leftAt", (store.positions[1] ?: 0) >= leftAt)
        } finally {
            process.cancel()
        }
    }

    @Test
    fun aControllerReplacingThePlaylistSavesWhereTheEpisodeItLeftWas() {
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        val leftAt = h.player.currentPosition
        // What the session does with a controller's setMediaItems after SessionCallback resolved them.
        h.engine.sessionPlayer.setMediaItems(listOf(h.store.episodes[2]!!.toMediaItem(useFile = false)), 0, 0)
        idle()
        assertEquals("2", h.player.currentMediaItem?.mediaId)
        assertTrue("saved ${h.store.positions[1]}, left at $leftAt", (h.store.positions[1] ?: 0) >= leftAt)
    }

    @Test
    fun aServiceStartedRightAfterAnotherEndedRestoresItsLastPositionEvenWhileThatIsStillBeingWritten() {
        val store = FakePlaybackStore().apply { add(1); queue.value = listOf(1) }
        val handoff = SaveHandoff()
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val first = Harness(store, handoff = handoff, persistScope = process).also { harnesses += it }.start()
            first.engine.playEpisode(1)
            first.playUntil(5_000)
            first.engine.sessionPlayer.pause()
            idle()
            val committed = store.positions[1]!!
            // The last writes are slow to land (the database is busy) while the service ends and a new one starts.
            val writes = CompletableDeferred<Unit>()
            store.beforeSave = { _, _ -> writes.await() }
            first.engine.sessionPlayer.seekTo(40_000)
            idle()
            first.release()
            assertEquals(committed, store.positions[1])
            val second = Harness(store, handoff = handoff, persistScope = process).also { harnesses += it }.start()
            store.beforeSave = { _, _ -> }
            writes.complete(Unit)
            idle()
            assertEquals(40_000L, store.positions[1])
            assertEquals("the new service starts where the last one ended", 40_000L, second.player.currentPosition)
        } finally {
            process.cancel()
        }
    }

    @Test
    fun aServiceStartedAfterTheLastWriteLandedRestoresIt() {
        val store = FakePlaybackStore().apply { add(1); queue.value = listOf(1) }
        val handoff = SaveHandoff()
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val first = Harness(store, handoff = handoff, persistScope = process).also { harnesses += it }.start()
            first.engine.playEpisode(1)
            first.playUntil(5_000)
            first.engine.sessionPlayer.pause()
            first.engine.sessionPlayer.seekTo(40_000)
            idle()
            first.release()
            assertEquals(40_000L, store.positions[1])
            val second = Harness(store, handoff = handoff, persistScope = process).also { harnesses += it }.start()
            assertEquals(40_000L, second.player.currentPosition)
        } finally {
            process.cancel()
        }
    }

    @Test
    fun theEndOfEpisodeTimerOnTheLastEpisodeCompletesItOnce() {
        fun playedActions(timer: Boolean): Int {
            val h = harness().apply { store.add(1, durationMs = 20_000); store.queue.value = listOf(1) }.start()
            h.engine.playEpisode(1)
            idle()
            if (timer) h.engine.sleepAtEndOfEpisode()
            run(h.player).untilPlayWhenReadyIs(false)
            idle()
            return h.store.actions.count { it == 1L to EpisodeActionKind.Played }
        }
        assertEquals(playedActions(timer = false), playedActions(timer = true))
    }

    @Test
    fun silenceAtASpotThatRecoveredBeforeIsReloadedAgainAfterASeek() {
        val h = harness().apply { store.add(1); store.queue.value = listOf(1) }.start()
        var buffers = 0
        var frozen = false
        h.renderedAudioBuffers = { if (!frozen) ++buffers else buffers }
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        frozen = true
        val soundStopped = h.player.currentPosition
        runMainLooperUntil { h.store.positionLog.any { it.cause == PositionCause.SilentRecovery } }
        idle()
        frozen = false
        h.playUntil(soundStopped + 30_000)
        // The listener goes back to that spot, and the sound drops there again: a new outage, so reload.
        h.engine.sessionPlayer.seekTo(soundStopped)
        idle()
        frozen = true
        runMainLooperUntil { h.store.positionLog.count { it.cause == PositionCause.SilentRecovery } == 2 || !h.player.playWhenReady }
        idle()
        assertEquals(2, h.store.positionLog.count { it.cause == PositionCause.SilentRecovery })
        assertTrue(h.player.playWhenReady)
    }
}
