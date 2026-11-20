/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.model.PlaybackSettings

/** The session is exported, so every other app is treated as hostile (CLAUDE.md "Security"). */
@RunWith(AndroidJUnit4::class)
class SessionCallbackTest {
    private val h = Harness().apply {
        store.add(1)
        store.add(2)
        store.positions[1] = 42_000
        store.queue.value = listOf(1, 2)
    }.start()
    private val callback = SessionCallback(h.engine, h.scope)
    private val session = MediaSession.Builder(appContext(), h.engine.sessionPlayer).setId("test").setCallback(callback).build()

    @After
    fun tearDown() {
        session.release()
        h.release()
    }

    private fun controller(uid: Int, ownUi: Boolean) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "some.package", 0, uid,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        false, Bundle().apply { if (ownUi) putBoolean(PlaybackClient.HINT_OWN_UI, true) }, true,
    )

    private val ownUi = controller(Process.myUid(), ownUi = true)
    private val otherApp = controller(10_999, ownUi = true)

    private fun <T> await(future: com.google.common.util.concurrent.ListenableFuture<T>): T {
        idle()
        return future.get()
    }

    @Test
    fun onlyOurOwnScreensGetTheCustomCommands() {
        val own = callback.onConnect(session, ownUi)
        assertTrue(own.availableSessionCommands.contains(SessionCommand(SessionCallback.Commands.PLAY_EPISODE, Bundle.EMPTY)))
        val other = callback.onConnect(session, otherApp)
        assertTrue(other.isAccepted)
        assertFalse(other.availableSessionCommands.contains(SessionCommand(SessionCallback.Commands.PLAY_EPISODE, Bundle.EMPTY)))
        assertTrue("other controllers keep play/pause and seeking", other.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    /**
     * PROGRESS "Later": the system once listed 98 controllers on our session after ~15 h. Each controller that
     * connects or leaves is logged by package with the running count, so the owner's logs show who piles up.
     */
    @Test
    fun controllersComingAndGoingAreLoggedByPackageWithTheCount() {
        callback.onConnect(session, otherApp)
        callback.onConnect(session, ownUi)
        callback.onDisconnected(session, otherApp)
        assertEquals(
            listOf(
                "Controller connected: some.package (1 connected)",
                "Controller connected: some.package (2 connected)",
                "Controller left: some.package (1 connected)",
            ),
            h.store.logLines.filter { it.startsWith("Controller") },
        )
    }

    @Test
    fun customCommandsFromAnotherAppAreRefused() {
        val result = await(
            callback.onCustomCommand(session, otherApp, SessionCommand(SessionCallback.Commands.PLAY_EPISODE, Bundle.EMPTY), Bundle().apply { putLong(SessionCallback.Commands.ARG_EPISODE_ID, 2) }),
        )
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        assertEquals(listOf(1L, 2L), h.store.queue.value)
        assertFalse(h.player.playWhenReady)
    }

    @Test
    fun ourScreensCanPlayAnEpisode() {
        val result = await(
            callback.onCustomCommand(session, ownUi, SessionCommand(SessionCallback.Commands.PLAY_EPISODE, Bundle.EMPTY), Bundle().apply { putLong(SessionCallback.Commands.ARG_EPISODE_ID, 2) }),
        )
        idle()
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        // Episode 1 was started (42 s) and is left for 2, so it leaves the queue (owner, 2026-11-18).
        assertEquals(listOf(2L), h.store.queue.value)
        assertTrue(h.player.playWhenReady)
    }

    @Test
    fun ourScreensCanStartAnEpisodeAtASpot() {
        val args = Bundle().apply {
            putLong(SessionCallback.Commands.ARG_EPISODE_ID, 2)
            putLong(SessionCallback.Commands.ARG_START_MS, 90_000)
        }
        val result = await(callback.onCustomCommand(session, ownUi, SessionCommand(SessionCallback.Commands.PLAY_EPISODE, Bundle.EMPTY), args))
        idle()
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(h.store.positionLog.any { it.episodeId == 2L && it.toMs == 90_000L })
        assertTrue(h.player.playWhenReady)
    }

    @Test
    fun itemsAControllerSendsAreLookedUpByIdAndTheirUrisNeverUsed() {
        val hostile = listOf(
            MediaItem.Builder().setMediaId("1").setUri("file:///data/data/us.dikarya.podplayer/databases/podplayer.db").build(),
        )
        val set = await(callback.onSetMediaItems(session, otherApp, hostile.toMutableList(), 0, 999_999))
        val source = ItemSource.of(set.mediaItems.first())!!
        assertEquals(1L, source.episodeId)
        assertNull(source.file)
        assertEquals("https://media.example.com/1.mp3", source.streamUrl)
        assertEquals("the saved position, not the controller's", 42_000L, set.startPositionMs)
        assertEquals(listOf("1", "2"), set.mediaItems.map { it.mediaId })

        val unknown = listOf(MediaItem.Builder().setMediaId("404").setUri("https://evil.example/x.mp3").build())
        assertTrue(await(callback.onSetMediaItems(session, otherApp, unknown.toMutableList(), 0, 0)).mediaItems.isEmpty())
        assertTrue(await(callback.onAddMediaItems(session, otherApp, unknown.toMutableList())).isEmpty())
        assertEquals(listOf("2"), await(callback.onAddMediaItems(session, otherApp, mutableListOf(MediaItem.Builder().setMediaId("2").setUri("content://x").build()))).map { it.mediaId })
    }

    @Test
    fun resumptionOffersTheLibrarysItems() {
        h.store.current = 1
        val items = await(callback.onPlaybackResumption(session, otherApp, true))
        assertEquals(listOf("1", "2"), items.mediaItems.map { it.mediaId })
        assertEquals(42_000L, items.startPositionMs)
        h.store.current = null
        h.store.queue.value = emptyList()
        assertTrue(await(callback.onPlaybackResumption(session, otherApp, false)).mediaItems.isEmpty())
    }

    @Test
    fun theLockScreenAndNotificationShowSkipBackAndForwardWithTheOwnersIntervals() {
        val default = SessionCallback.buttons(appContext(), PlaybackSettings())
        assertEquals(listOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD), default.map { it.playerCommand })
        assertEquals(listOf(CommandButton.ICON_SKIP_BACK_15, CommandButton.ICON_SKIP_FORWARD_30), default.map { it.icon })
        assertEquals(listOf("Back 15 seconds", "Forward 30 seconds"), default.map { it.displayName.toString() })
        val custom = SessionCallback.buttons(appContext(), PlaybackSettings(skipBackSeconds = 20, skipForwardSeconds = 45))
        // Controllers that know Media3's icons (Android Auto) still get "skip back/forward"; the notification gets
        // our numbered drawable.
        assertEquals(listOf(CommandButton.ICON_SKIP_BACK, CommandButton.ICON_SKIP_FORWARD), custom.map { it.icon })
        assertEquals(listOf(R.drawable.ic_skip_back_20, R.drawable.ic_skip_forward_45), custom.map { it.iconResId })
    }

    @Test
    fun everySkipIntervalTheOwnerCanChooseHasANumberedIcon() {
        val plainBack = CommandButton.getIconResIdForIconConstant(CommandButton.ICON_SKIP_BACK)
        val plainForward = CommandButton.getIconResIdForIconConstant(CommandButton.ICON_SKIP_FORWARD)
        for (seconds in SkipIntervals.BACK_SECONDS) {
            val back = SessionCallback.buttons(appContext(), PlaybackSettings(skipBackSeconds = seconds)).first()
            assertTrue("back $seconds s shows a plain arrow", back.iconResId != 0 && back.iconResId != plainBack)
        }
        for (seconds in SkipIntervals.FORWARD_SECONDS) {
            val forward = SessionCallback.buttons(appContext(), PlaybackSettings(skipForwardSeconds = seconds)).last()
            assertTrue("forward $seconds s shows a plain arrow", forward.iconResId != 0 && forward.iconResId != plainForward)
        }
    }
}
