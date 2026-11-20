/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
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

    /**
     * The owner's phone, 2026-11-18: the next queued episode had been played to 34 s, and it started over at 0.
     * Every other way into an episode starts at its saved position; moving on to the next one does too.
     */
    @Test
    fun theNextEpisodeStartsAtItsSavedPosition() {
        val h = harness().apply {
            store.add(1, durationMs = 20_000); store.add(2); store.add(3)
            store.positions[2] = 34_000
            store.queue.value = listOf(1, 2, 3)
        }.start()
        h.engine.playEpisode(1)
        run(h.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        idle()
        assertEquals(2L, h.store.current)
        assertTrue("at ${h.player.currentPosition}", h.player.currentPosition >= 34_000)
        assertTrue(h.player.playWhenReady)
        val next = h.store.positionLog.last { it.cause == PositionCause.NextEpisode }
        assertEquals(2L, next.episodeId)
        assertEquals(34_000L, next.toMs)
    }

    /**
     * Owner, 2026-11-18: "if I listen to one podcast, then switch to another, I don't want the first one to be in
     * the queue. If I switch out, I probably lost interest; if I want to pick it up again, I will." Its position
     * stays saved, so picking it up again resumes it.
     */
    @Test
    fun switchingToAnotherEpisodeTakesTheOneYouLeftOutOfTheQueueKeepingItsPosition() {
        val h = harness().apply { (1L..3L).forEach { store.add(it) }; store.queue.value = listOf(1, 2, 3) }.start()
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        h.engine.playEpisode(3)
        idle()
        assertEquals(listOf(3L, 2L), h.store.queue.value)
        assertEquals(listOf(3L, 2L), h.itemIds())
        assertTrue("saved at ${h.store.positions[1]}", (h.store.positions[1] ?: 0) >= 5_000)
        assertFalse(1L in h.store.played)
        // Picking it up again resumes it. Episode 3 was only loaded, never listened to (still at 0), so it stays.
        h.engine.playEpisode(1)
        idle()
        assertTrue("at ${h.player.currentPosition}", h.player.currentPosition >= 5_000)
        assertEquals(listOf(1L, 3L, 2L), h.store.queue.value)
    }

    @Test
    fun anEpisodeOnlyWaitingAtTheHeadOfTheQueueStaysWhenAnotherIsPlayed() {
        // Loaded paused at 0 when the app started, never listened to: playing another doesn't take it out.
        val h = harness().apply { (1L..3L).forEach { store.add(it) }; store.queue.value = listOf(1, 2) }.start()
        assertEquals(1L, h.store.current)
        h.engine.playEpisode(3)
        idle()
        assertEquals(listOf(3L, 1L, 2L), h.store.queue.value)
    }

    /**
     * The owner's phone, 2026-11-18: after unsubscribing from the show whose episode was loaded, the mini player
     * kept it and Play tried it. An episode that leaves the library leaves the player; the queue's head loads paused.
     */
    @Test
    fun anEpisodeThatLeavesTheLibraryLeavesThePlayer() {
        val h = harness().apply {
            store.add(1, feedId = 7); store.add(2, feedId = 8)
            store.queue.value = listOf(1, 2)
        }.start()
        h.engine.playEpisode(1)
        h.playUntil(2_000)
        // Unsubscribing deletes the show's episodes and their queue entries.
        h.store.episodes.remove(1L)
        h.store.queue.value = listOf(2)
        idle()
        assertEquals(listOf(2L), h.itemIds())
        assertEquals(2L, h.store.current)
        assertFalse(h.player.playWhenReady)
    }

    /**
     * The owner's phone, 2026-11-17: the position ran on with no sound. When no audio reaches the output while the
     * position moves 15 s, the episode is loaded again from where the sound stopped, and it keeps playing.
     */
    @Test
    fun playingWithoutSoundReloadsFromWhereTheSoundStopped() {
        val h = harness().apply { store.add(1); store.queue.value = listOf(1) }.start()
        var buffers = 0
        var frozen = false
        h.renderedAudioBuffers = { if (!frozen) ++buffers else buffers }
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        frozen = true
        val soundStopped = h.player.currentPosition
        // The reload puts the position back, so wait for the recovery itself.
        androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil {
            h.store.positionLog.any { it.cause == PositionCause.SilentRecovery }
        }
        idle()
        val recovery = h.store.positionLog.single { it.cause == PositionCause.SilentRecovery }
        // Within a tick or so of where the sound stopped (the watch looks every TICK_MS).
        assertTrue("reloaded at ${recovery.toMs}, sound stopped near $soundStopped", recovery.toMs in soundStopped - 1_000..soundStopped + 1_000)
        // The reload brought the sound back: it plays on.
        frozen = false
        h.playUntil(soundStopped + 40_000)
        assertEquals(1, h.store.positionLog.count { it.cause == PositionCause.SilentRecovery })
        assertTrue(h.player.playWhenReady)
        assertTrue(h.store.logLines.any { "without sound" in it })
    }

    /** If loading it again doesn't bring the sound back, it pauses there instead of reloading every 15 s. */
    @Test
    fun stillSilentAfterReloadingPausesInsteadOfLooping() {
        val h = harness().apply { store.add(1); store.queue.value = listOf(1) }.start()
        var buffers = 0
        var frozen = false
        h.renderedAudioBuffers = { if (!frozen) ++buffers else buffers }
        h.engine.playEpisode(1)
        h.playUntil(5_000)
        frozen = true
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertEquals(1, h.store.positionLog.count { it.cause == PositionCause.SilentRecovery })
        assertTrue(h.store.logLines.any { "still without sound" in it })
    }

    @Test
    fun theLastEpisodeEndingStaysLoaded() {
        // Owner, 2026-11-16: when the last episode ends, the player stays on it instead of "Nothing is playing".
        val h = harness().apply { store.add(1, durationMs = 10_000); store.queue.value = listOf(1) }.start()
        h.engine.playEpisode(1)
        // The engine reacts to STATE_ENDED within the same callback, so wait for the pause it causes.
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertTrue(1L in h.store.played)
        assertEquals(emptyList<Long>(), h.store.queue.value)
        assertEquals(1L, h.store.current)
        assertEquals(listOf(1L), h.itemIds())
    }

    @Test
    fun playOnAnEndedEpisodeStartsItOver() {
        val h = harness().apply { store.add(1, durationMs = 10_000); store.queue.value = listOf(1) }.start()
        h.engine.playEpisode(1)
        run(h.player).untilPlayWhenReadyIs(false)
        idle()
        assertEquals(Player.STATE_ENDED, h.player.playbackState)
        h.engine.sessionPlayer.play()
        // Checked before idling: Play seeks synchronously, while idling lets the auto-advancing fake clock play on for
        // a varying stretch (this flaked on CI when it read the position afterwards).
        assertTrue(h.player.playWhenReady)
        assertTrue("starts again from the beginning", h.player.currentPosition < 1_000)
        idle()
        assertEquals(1L, h.store.current)
    }

    @Test
    fun reaching98PercentCountsAsPlayedWhileTheEpisodeStaysLoaded() {
        val h = harness().apply { store.add(1, durationMs = 100_000); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.pauseAfter(5_000)
        // Seeks are exact, unlike playing to a position: just under 98%, then exactly 98% (owner, 2026-11-15).
        h.engine.sessionPlayer.seekTo(97_900)
        idle()
        assertFalse(1L in h.store.played)
        h.engine.sessionPlayer.seekTo(98_000)
        idle()
        assertTrue("98% of an episode counts as played", 1L in h.store.played)
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
        // From 90 s, 45 s forward reaches the end (the player settles a millisecond before it).
        val skipped = h.store.positionLog.last { it.cause == PositionCause.SkipForward }
        assertTrue("the skip went to the end: $skipped", skipped.fromMs >= 90_000 && skipped.toMs in 99_000..100_000)
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
        // It is already playing, and idling lets the auto-advancing fake clock run on for a varying stretch (a 5 s
        // bound flaked on CI), so the check is only that the saved 500 s were not used.
        assertTrue("at ${h.player.currentPosition}", h.player.currentPosition < 400_000)
        assertFalse(h.store.positionLog.any { it.cause == PositionCause.Restore })
    }

    // --- Starting at a spot (M1-S8: timestamps in show notes seek within their episode, SPEC §5) ---

    @Test
    fun aTimestampStartsItsEpisodeThereAndPutsItAtTheTopOfTheQueue() {
        val h = harness().apply { store.add(1); store.add(2); store.positions[2] = 50_000; store.queue.value = listOf(1) }.start()
        h.engine.playEpisode(2, startAtMs = 120_000)
        idle()
        assertEquals(listOf(2L, 1L), h.store.queue.value)
        assertEquals(listOf(2L, 1L), h.itemIds())
        assertTrue(h.player.playWhenReady)
        // The fake clock runs on while the test waits, so check the logged jump rather than the live position.
        val jump = h.store.positionLog.single { it.episodeId == 2L }
        assertEquals(PositionCause.Seek, jump.cause)
        assertEquals(50_000L, jump.fromMs)
        assertEquals(120_000L, jump.toMs)
        assertTrue(h.player.currentPosition >= 120_000)
    }

    @Test
    fun aTimestampInTheLoadedEpisodeSeeksWithoutReloadingIt() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.pauseAfter(5_000)
        val sources = h.sources.created.size
        h.engine.playEpisode(1, startAtMs = 200_000)
        idle()
        assertEquals("the same source keeps playing", sources, h.sources.created.size)
        assertTrue(h.player.playWhenReady)
        assertTrue(h.store.positionLog.any { it.cause == PositionCause.Seek && it.toMs == 200_000L })
        assertEquals(200_000L, h.lastSave(1))
    }

    @Test
    fun playingTheLoadedEpisodeAgainKeepsItsPosition() {
        val h = harness().apply { store.add(1) }.start()
        h.engine.playEpisode(1)
        h.pauseAfter(20_000)
        val pausedAt = h.player.currentPosition
        // A save older than the live position must not pull the episode back.
        h.store.positions[1] = 1_000
        val sources = h.sources.created.size
        h.engine.playEpisode(1)
        idle()
        assertEquals(sources, h.sources.created.size)
        assertTrue(h.player.playWhenReady)
        assertTrue("at $pausedAt or later: ${h.player.currentPosition}", h.player.currentPosition >= pausedAt)
        assertFalse(h.store.positionLog.any { it.cause == PositionCause.Restore })
    }

    @Test
    fun aTimestampPastTheEndIsHeldToTheEpisode() {
        val h = harness().apply { store.add(1, durationMs = 100_000); store.add(2); store.queue.value = listOf(1, 2) }.start()
        h.engine.playEpisode(1)
        h.pauseAfter(1_000)
        val seeksBefore = h.store.positionLog.count { it.cause == PositionCause.Seek }
        h.engine.playEpisode(1, startAtMs = 5_000_000)
        idle()
        val seek = h.store.positionLog.filter { it.cause == PositionCause.Seek }.drop(seeksBefore).first()
        // The player settles a millisecond before the end.
        assertTrue("the timestamp was used, held to the episode's end: $seek", seek.episodeId == 1L && seek.toMs in 99_000..100_000)
        assertTrue(h.store.saves.none { it.second > 100_000 })
        assertTrue(h.store.positionLog.none { it.toMs > 100_000 })
    }

    // --- Artwork for the playing episode (PROGRESS "Later" S8/S9: the lock screen had none if never shown) ---

    @Test
    fun artworkThatWasNotCachedIsFetchedAndShownWithoutInterruptingPlayback() {
        val art = Uri.parse("content://us.dikarya.podplayer.artwork/1.jpg")
        val h = harness().apply { store.add(1); store.add(2); store.queue.value = listOf(1, 2); store.fetchableArtwork[1] = art }.start()
        h.engine.playEpisode(1)
        h.playUntil(3_000)
        assertEquals(art, h.player.currentMediaItem?.mediaMetadata?.artworkUri)
        assertEquals("the audio source wasn't rebuilt", 1, h.sources.created.count { it.first == 1L })
        assertTrue(h.player.isPlaying)
        assertTrue(h.player.currentPosition >= 3_000)
        assertEquals(listOf(1L, 2L), h.itemIds())
        assertEquals(1L, h.store.current)
        assertFalse(h.store.positionLog.any { it.cause == PositionCause.Unexplained })
    }

    @Test
    fun cachedArtworkIsUsedAsItIsAndAnEpisodeWithoutAnyKeepsPlaying() {
        val cached = Uri.parse("content://us.dikarya.podplayer.artwork/cached.jpg")
        val h = harness().apply { store.add(1, artworkUri = cached); store.add(2) }.start()
        h.engine.playEpisode(1)
        idle()
        assertEquals(cached, h.player.currentMediaItem?.mediaMetadata?.artworkUri)
        assertFalse(1L in h.store.artworkFetches)
        h.engine.playEpisode(2)
        h.playUntil(1_000)
        assertTrue(2L in h.store.artworkFetches)
        assertNull(h.player.currentMediaItem?.mediaMetadata?.artworkUri)
        assertTrue(h.player.isPlaying)
    }

    @Test
    fun theNextEpisodesArtworkIsFetchedWhenItStarts() {
        val art = Uri.parse("content://us.dikarya.podplayer.artwork/2.jpg")
        val h = harness().apply {
            store.add(1, durationMs = 10_000); store.add(2); store.queue.value = listOf(1, 2); store.fetchableArtwork[2] = art
        }.start()
        h.engine.playEpisode(1)
        run(h.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        idle()
        assertEquals(2L, h.store.current)
        assertEquals(art, h.player.currentMediaItem?.mediaMetadata?.artworkUri)
        assertTrue(h.player.playWhenReady)
    }
}
