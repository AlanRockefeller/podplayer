/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.model.PlaybackSettings

/**
 * Who may do what with the session. The service is exported (the lock screen, Bluetooth, cars and Android Auto
 * must reach it), so every other app's request is treated as hostile:
 * - our own screens (uid and [PlaybackClient.HINT_OWN_UI]) get the custom commands; nobody else does;
 * - items a controller sends are looked up by episode id in the library; their URIs are never played, so no
 *   app can make us fetch a URL or read a file;
 * - every Play goes through the Bluetooth play gate in [PodPlayer].
 */
class SessionCallback(
    private val engine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val ownUid: Int = Process.myUid(),
) : MediaLibrarySession.Callback {
    fun isOwnUi(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == ownUid && controller.connectionHints.getBoolean(PlaybackClient.HINT_OWN_UI, false)

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        engine.controllerLeft(controller.packageName)
    }

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        engine.controllerConnected(controller.packageName)
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        if (isOwnUi(controller)) {
            val commands: SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .apply { Commands.ALL.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }
                .build()
            builder.setAvailableSessionCommands(commands)
        }
        return builder.build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (!isOwnUi(controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        when (customCommand.customAction) {
            Commands.PLAY_EPISODE -> engine.playEpisode(
                args.getLong(Commands.ARG_EPISODE_ID, -1),
                startAtMs = if (args.containsKey(Commands.ARG_START_MS)) args.getLong(Commands.ARG_START_MS) else null,
            )
            Commands.SET_SPEED -> engine.setSpeed(
                if (args.containsKey(Commands.ARG_SPEED)) args.getFloat(Commands.ARG_SPEED) else null,
                forShow = args.getBoolean(Commands.ARG_FOR_SHOW, false),
            )
            Commands.SLEEP_MINUTES -> engine.sleepAfterMinutes(args.getInt(Commands.ARG_MINUTES, 0))
            Commands.SLEEP_END_OF_EPISODE -> engine.sleepAtEndOfEpisode()
            Commands.SLEEP_CANCEL -> engine.cancelSleepTimer()
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = future {
        // Resolution only: Media3 uses this for additions and replacements alike. An addition reaches the database
        // queue through PodPlayer's add calls.
        engine.itemsFor(mediaItems.map { it.mediaId }).toMutableList()
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        // Whatever the controller asked for, the player gets the library's items: the chosen episode from its
        // saved position, then the queue's next. Positions a controller sends are ignored (no unintended jumps).
        val wanted = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
        val id = wanted?.mediaId?.toLongOrNull()
        engine.itemsStartingWith(id) ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        engine.resumptionItems() ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
    }

    // Android Auto's browse tree comes in M1-S10; until then the root is empty.

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(ROOT, params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))

    private fun <T : Any> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (t: Throwable) {
                result.setException(t)
            }
        }
        return result
    }

    /** Custom commands our own screens send. */
    object Commands {
        const val PLAY_EPISODE = "us.dikarya.podplayer.PLAY_EPISODE"
        const val SET_SPEED = "us.dikarya.podplayer.SET_SPEED"
        const val SLEEP_MINUTES = "us.dikarya.podplayer.SLEEP_MINUTES"
        const val SLEEP_END_OF_EPISODE = "us.dikarya.podplayer.SLEEP_END_OF_EPISODE"
        const val SLEEP_CANCEL = "us.dikarya.podplayer.SLEEP_CANCEL"
        val ALL = listOf(PLAY_EPISODE, SET_SPEED, SLEEP_MINUTES, SLEEP_END_OF_EPISODE, SLEEP_CANCEL)

        const val ARG_EPISODE_ID = "episode_id"

        /** Optional with [PLAY_EPISODE]: start there instead of at the saved position. */
        const val ARG_START_MS = "start_ms"
        const val ARG_SPEED = "speed"
        const val ARG_FOR_SHOW = "for_show"
        const val ARG_MINUTES = "minutes"
    }

    companion object {
        private val ROOT: MediaItem = MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
            .build()

        /** Notification and lock-screen buttons: skip back and forward by the owner's intervals (SPEC §10). */
        fun buttons(context: Context, settings: PlaybackSettings): List<CommandButton> = listOf(
            CommandButton.Builder(backIcon(settings.skipBackSeconds))
                .apply { SkipIntervals.backIcon(settings.skipBackSeconds)?.let(::setCustomIconResId) }
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(context.resources.getQuantityString(R.plurals.playback_skip_back, settings.skipBackSeconds, settings.skipBackSeconds))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(forwardIcon(settings.skipForwardSeconds))
                .apply { SkipIntervals.forwardIcon(settings.skipForwardSeconds)?.let(::setCustomIconResId) }
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(context.resources.getQuantityString(R.plurals.playback_skip_forward, settings.skipForwardSeconds, settings.skipForwardSeconds))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )

        private fun backIcon(seconds: Int) = when (seconds) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            10 -> CommandButton.ICON_SKIP_BACK_10
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }

        private fun forwardIcon(seconds: Int) = when (seconds) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }
    }
}
