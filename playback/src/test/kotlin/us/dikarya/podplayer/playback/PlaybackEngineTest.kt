/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.core.model.PositionCause

/** SPEC §10 and CLAUDE.md "Playback": one test per rule, written before the engine. */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val harnesses = mutableListOf<Harness>()

    private fun harness(store: FakePlaybackStore = FakePlaybackStore()): Harness = Harness(store).also { harnesses += it }

    @After
    fun tearDown() {
        harnesses.forEach { runCatching { it.release() } }
    }

    private fun Harness.itemIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.toLong() }

    private fun file(name: String) = File(tmp.root, name).apply { writeText("audio") }

    private fun Harness.playUntil(positionMs: Long) {
        run(player).untilPositionAtLeast(positionMs)
        idle()
    }

    private fun Harness.lastSave(id: Long) = store.saves.last { it.first == id }.second

    // --- Queue projection (CLAUDE.md: the database queue is the truth; the player holds current + next) ---

    @Test
    fun playingAnEpisodePutsItAtTheTopOfTheQueueAndLoadsOnlyTheNextOne() {
        val h = harness().apply { (1L..4L).forEach { store.add(it) }; store.queue.value = listOf(2, 3, 4) }.start()
        h.engine.playEpisode(1)
        idle()
        assertEquals(listOf(1L, 2L, 3L, 4L), h.store.queue.value)
        assertEquals(listOf(1L, 2L), h.itemIds())
        assertEquals(1L, h.store.current)
        assertTrue(h.player.playWhenReady)
    }

    @Test
    fun aQueueChangeRebuildsTheNextItemWithoutInterruptingPlayback() {
        val h = harness().apply { (1L..3L).forEach { store.add(it) }; store.queue.value = listOf(1, 2, 3) }.start()
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        h.store.queue.value = listOf(1, 3, 2)
        idle()
        assertEquals(listOf(1L, 3L), h.itemIds())
        assertEquals(0, h.player.currentMediaItemIndex)
        assertTrue(h.player.isPlaying)
        assertTrue(h.player.currentPosition >= 5_000)
        // Removing the playing episode from the queue doesn't stop it.
        h.store.queue.value = listOf(3, 2)
        idle()
        assertEquals(listOf(1L, 3L), h.itemIds())
        assertTrue(h.player.isPlaying)
    }

    @Test
    fun finishingAnEpisodeMarksItPlayedRemovesItFromTheQueueAndAdvances() {
        val h = harness().apply {
            store.add(1, durationMs = 20_000); store.add(2); store.add(3)
            store.queue.value = listOf(1, 2, 3)
        }.start()
        h.engine.playEpisode(1)
        run(h.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        idle()
        assertTrue(1L in h.store.played)
        assertEquals(listOf(2L, 3L), h.store.queue.value)
        assertEquals(2L, h.store.current)
        assertEquals(listOf(2L, 3L), h.itemIds())
        assertTrue(h.player.playWhenReady)
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.NextEpisode && it.episodeId == 2L })
        assertTrue(1L to EpisodeActionKind.Played in h.store.actions)
    }

    @Test
    fun theLastEpisodeEndingLeavesNothingLoaded() {
        val h = harness().apply { store.add(1, durationMs = 10_000); store.queue.value = listOf(1) }.start()
        h.engine.playEpisode(1)
        // The engine reacts to STATE_ENDED within the same callback, so wait for the stop it causes.
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertTrue(1L in h.store.played)
        assertEquals(emptyList<Long>(), h.store.queue.value)
        assertNull(h.store.current)
        assertEquals(0, h.player.mediaItemCount)
    }

    @Test
    fun reaching99PercentCountsAsPlayedWhileTheEpisodeStaysLoaded() {
        val h = harness().apply { store.add(1, durationMs = 100_000); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.pauseAfter(5_000)
        // Seeks are exact, unlike playing to a position: just under 99%, then exactly 99%.
        h.engine.sessionPlayer.seekTo(98_900)
        idle()
        assertFalse(1L in h.store.played)
        h.engine.sessionPlayer.seekTo(99_000)
        idle()
        assertTrue("99% of an episode counts as played", 1L in h.store.played)
        assertEquals(1L, h.store.current)
        assertEquals(listOf(1L, 2L), h.store.queue.value)
    }

    // --- Positions (CLAUDE.md: never lose a position) ---

    @Test
    fun positionIsSavedOnPause() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.playUntil(12_000)
        h.engine.sessionPlayer.pause()
        idle()
        assertTrue(h.lastSave(1) >= 12_000)
        assertTrue(1L to EpisodeActionKind.Pause in h.store.actions)
    }

    @Test
    fun positionIsSavedEveryTenSecondsWhilePlaying() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.playUntil(31_000)
        val whilePlaying = h.store.saves.filter { it.first == 1L }.map { it.second }
        assertTrue("saves: $whilePlaying", whilePlaying.count { it in 9_000..31_000 } >= 3)
        assertTrue(h.player.isPlaying)
    }

    @Test
    fun positionIsSavedOnSeekAndStop() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.playUntil(2_000)
        h.engine.sessionPlayer.seekTo(300_000)
        idle()
        assertEquals(300_000L, h.lastSave(1))
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.Seek && it.toMs == 300_000L })
        h.playUntil(304_000)
        h.engine.sessionPlayer.stop()
        idle()
        assertTrue(h.lastSave(1) >= 304_000)
    }

    @Test
    fun positionIsSavedOnAudioFocusLossTransientOrNot() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.playUntil(3_000)
        val focus = shadowOf(appContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest
        assertNotNull("the player asked for audio focus (handleAudioFocus = true)", focus)
        val savesBefore = h.store.saves.size
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        assertTrue("a transient loss (a call, a navigation prompt) saves", h.store.saves.size > savesBefore)
        assertTrue("and pauses rather than ducks", !h.player.isPlaying)
        assertTrue("then resumes by itself", h.player.playWhenReady)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        h.playUntil(6_000)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertFalse(h.player.playWhenReady)
        assertTrue(h.lastSave(1) >= 6_000)
    }

    @Test
    fun becomingNoisyPausesAndSaves() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.playUntil(4_000)
        appContext().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertFalse("headphones or Bluetooth disconnect pauses", h.player.playWhenReady)
        assertTrue(h.lastSave(1) >= 4_000)
    }

    @Test
    fun positionIsSavedWhenTheServiceEnds() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.playUntil(7_000)
        h.release()
        assertTrue(h.lastSave(1) >= 7_000)
    }

    @Test
    fun afterProcessDeathTheEpisodeComesBackWithinTenSecondsAndDoesNotStartByItself() {
        val store = FakePlaybackStore().apply { add(1); add(2); queue.value = listOf(1, 2) }
        val first = harness(store).start()
        first.engine.playEpisode(1)
        first.playUntil(25_000)
        val stoppedAt = first.player.currentPosition
        first.kill()

        val second = harness(store).start()
        assertEquals(listOf(1L, 2L), second.itemIds())
        val restored = second.player.currentPosition
        assertTrue("restored $restored, stopped $stoppedAt", restored in (stoppedAt - 10_000)..stoppedAt)
        assertFalse("never auto-start", second.player.playWhenReady)
    }

    @Test
    fun resumingStartsExactlyAtTheSavedPosition() {
        val store = FakePlaybackStore().apply { add(1); positions[1] = 123_456; current = 1 }
        val h = harness(store).start()
        assertEquals(123_456L, h.player.currentPosition)
        h.engine.sessionPlayer.play()
        idle()
        assertTrue("no rewind on resume by default", h.player.currentPosition >= 123_456)
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.Restore && it.toMs == 123_456L })
    }

    @Test
    fun rewindAfterPauseDecidesByTheSettingAndThePauseLength() {
        val on = PlaybackSettings(rewindAfterPause = true, rewindSeconds = 10, rewindAfterPauseMinutes = 10)
        assertFalse("off by default", PlaybackEngine.shouldRewindAfterPause(PlaybackSettings(), pausedForMs = 24 * 3_600_000L))
        assertFalse("a short pause keeps the position", PlaybackEngine.shouldRewindAfterPause(on, pausedForMs = 30_000))
        assertFalse(PlaybackEngine.shouldRewindAfterPause(on, pausedForMs = 10 * 60_000L - 1))
        assertTrue(PlaybackEngine.shouldRewindAfterPause(on, pausedForMs = 10 * 60_000L))
    }

    @Test
    fun aLongPauseRewindsWhenTurnedOn() {
        val store = FakePlaybackStore().apply {
            add(1)
            settings.value = PlaybackSettings(rewindAfterPause = true, rewindSeconds = 10, rewindAfterPauseMinutes = 10)
        }
        val h = harness(store).start()
        h.engine.playEpisode(1)
        h.pauseAfter(70_000)
        val pausedAt = h.player.currentPosition
        h.clock.advanceTime(20 * 60_000)
        h.engine.sessionPlayer.play()
        idle()
        // Playback continues after the rewind, so check the logged jump rather than the live position.
        val rewind = h.store.positionLog.single { it.cause == PositionCause.RewindAfterPause }
        assertEquals(pausedAt, rewind.fromMs)
        assertEquals(pausedAt - 10_000, rewind.toMs)
    }

    // --- Speed and skip intervals (SPEC §10) ---

    @Test
    fun globalSpeedAppliesAndAShowsOwnSpeedWinsWithPitchKept() {
        val store = FakePlaybackStore().apply {
            add(1, feedId = 1, durationMs = 10_000); add(2, feedId = 2)
            queue.value = listOf(1, 2)
            settings.value = PlaybackSettings(speed = 1.3f)
            showSpeeds[2] = 1.8f
        }
        val h = harness(store).start()
        h.engine.playEpisode(1)
        assertEquals(1.3f, h.player.playbackParameters.speed)
        assertEquals(1.0f, h.player.playbackParameters.pitch)
        run(h.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        idle()
        assertEquals(1.8f, h.player.playbackParameters.speed)
        assertEquals(1.0f, h.player.playbackParameters.pitch)
    }

    @Test
    fun settingTheSpeedForAShowLeavesTheGlobalSpeedAlone() {
        val h = harness().apply { store.add(1, feedId = 7) }.start()
        h.engine.playEpisode(1)
        idle()
        h.engine.setSpeed(2.0f, forShow = true)
        idle()
        assertEquals(2.0f, h.store.showSpeeds[7])
        assertEquals(1.0f, h.store.settings.value.speed)
        assertEquals(2.0f, h.player.playbackParameters.speed)
        h.engine.setSpeed(1.5f, forShow = false)
        idle()
        assertEquals(1.5f, h.store.settings.value.speed)
        assertEquals("the show's own speed still wins", 2.0f, h.player.playbackParameters.speed)
        h.engine.setSpeed(null, forShow = true)
        idle()
        assertEquals(1.5f, h.player.playbackParameters.speed)
    }

    @Test
    fun skipIntervalsDefaultTo15And30AreConfigurableAndClamped() {
        val h = harness().apply { store.add(1, durationMs = 100_000) }.start()
        h.engine.playEpisode(1)
        h.playUntil(40_000)
        h.engine.sessionPlayer.pause()
        idle()
        val p = h.player.currentPosition
        assertEquals(15_000L, h.engine.sessionPlayer.seekBackIncrement)
        assertEquals(30_000L, h.engine.sessionPlayer.seekForwardIncrement)
        h.engine.sessionPlayer.seekBack()
        idle()
        assertEquals(p - 15_000, h.player.currentPosition)
        h.engine.sessionPlayer.seekForward()
        idle()
        assertEquals(p + 15_000, h.player.currentPosition)
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.SkipBack })
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.SkipForward })

        h.store.settings.value = PlaybackSettings(skipBackSeconds = 10, skipForwardSeconds = 45)
        idle()
        assertEquals(10_000L, h.engine.sessionPlayer.seekBackIncrement)
        assertEquals(45_000L, h.engine.sessionPlayer.seekForwardIncrement)

        h.engine.sessionPlayer.seekTo(3_000)
        idle()
        h.engine.sessionPlayer.seekBack()
        idle()
        assertEquals("never before the start", 0L, h.player.currentPosition)
        h.engine.sessionPlayer.seekTo(90_000)
        idle()
        h.engine.sessionPlayer.seekForward()
        idle()
        assertTrue("never past the end", h.player.currentPosition <= 100_000)
    }

    @Test
    fun aCarsNextAndPreviousButtonsSkipInsteadOfChangingEpisode() {
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.playUntil(50_000)
        h.engine.sessionPlayer.pause()
        idle()
        val p = h.player.currentPosition
        h.engine.sessionPlayer.seekToNext()
        idle()
        assertEquals(1L, h.store.current)
        assertEquals(p + 30_000, h.player.currentPosition)
        h.engine.sessionPlayer.seekToPrevious()
        idle()
        assertEquals(p + 15_000, h.player.currentPosition)
    }

    // --- Play the downloaded file whenever there is one (owner, M1-S7 planning) ---

    @Test
    fun aDownloadedEpisodePlaysFromItsFileAndAStreamedOneStreams() {
        val h = harness().apply {
            store.add(1, file = file("1.mp3")); store.add(2)
            store.queue.value = listOf(1, 2)
        }.start()
        h.engine.playEpisode(1)
        idle()
        assertTrue(1L to true in h.sources.created)
        assertFalse(1L to false in h.sources.created)
        assertTrue(2L to false in h.sources.created)
    }

    @Test
    fun theNextEpisodeSwitchesToItsFileWhenItsDownloadFinishes() {
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.playUntil(3_000)
        h.store.add(2, file = file("2.mp3"))
        idle()
        assertTrue(2L to true in h.sources.created)
        assertEquals(listOf(1L, 2L), h.itemIds())
        assertTrue("the playing episode was not touched", h.player.isPlaying)
        assertFalse(1L to true in h.sources.created)
    }

    @Test
    fun theCurrentEpisodeSwitchesToItsFileAtTheNextPauseNeverMidPlay() {
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        h.store.add(1, file = file("1.mp3"))
        idle()
        h.playUntil(8_000)
        assertFalse("no switch while playing", 1L to true in h.sources.created)
        h.engine.sessionPlayer.pause()
        idle()
        val pausedAt = h.store.positions[1]!!
        assertTrue(1L to true in h.sources.created)
        assertEquals(listOf(1L, 2L), h.itemIds())
        assertFalse(h.player.playWhenReady)
        assertEquals(pausedAt, h.player.currentPosition)
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.SwitchToFile })
    }

    @Test
    fun aBrokenFileFallsBackToTheStreamAtTheSamePosition() {
        val h = harness().apply {
            store.add(1, file = file("1.mp3")); store.positions[1] = 30_000
            store.queue.value = listOf(1)
            sources.brokenFiles += 1L
        }.start()
        h.engine.playEpisode(1)
        // The engine handles the error inside the error callback, so the error is gone before a waiter sees it.
        idle()
        idle()
        run(h.player).untilState(Player.STATE_READY)
        idle()
        assertTrue(1L to false in h.sources.created)
        assertTrue(h.player.currentPosition >= 30_000)
        assertTrue(h.player.playWhenReady)
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.FallBackToStream })
        assertTrue(h.store.logLines.any { "stream" in it })
    }

    // --- Resumption and bookkeeping ---

    @Test
    fun resumptionOffersTheCurrentEpisodeThenTheQueueHeadThenNothing() = runBlocking {
        val store = FakePlaybackStore().apply { add(1); add(2); add(3); positions[2] = 42_000; queue.value = listOf(3, 2) }
        val h = harness(store).start()
        store.current = 2
        val current = h.engine.resumptionItems()!!
        assertEquals(listOf("2", "3"), current.mediaItems.map { it.mediaId })
        assertEquals(42_000L, current.startPositionMs)

        store.current = null
        assertEquals(listOf("3", "2"), h.engine.resumptionItems()!!.mediaItems.map { it.mediaId })

        store.queue.value = emptyList()
        assertNull(h.engine.resumptionItems())
    }

    @Test
    fun anUnknownEpisodeChangesNothing() {
        val h = harness().apply { store.add(1); store.queue.value = listOf(1) }.start()
        h.engine.playEpisode(999)
        idle()
        assertEquals(listOf(1L), h.store.queue.value)
        assertFalse(h.player.playWhenReady)
    }

    @Test
    fun aPlayedEpisodeStartsAgainFromTheBeginning() {
        val store = FakePlaybackStore().apply { add(1); positions[1] = 500_000; played += 1L }
        val h = harness(store).start()
        h.engine.playEpisode(1)
        idle()
        // It is already playing, so allow for the moment that has passed; the saved 500 s were not used.
        assertTrue(h.player.currentPosition < 5_000)
        assertFalse(h.store.positionLog.any { it.cause == PositionCause.Restore })
    }
}
