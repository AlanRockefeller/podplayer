/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review PR #5: the database queue is the source of truth for Up Next (CLAUDE.md), also for controllers. Media3
 * resolves a controller's added and replacing items through `onAddMediaItems`, then adds or replaces them on the
 * session's player: resolving stays resolving, and the player's add is what reaches the queue.
 */
@RunWith(AndroidJUnit4::class)
class SessionCallbackQueueTest {
    private val h = Harness().apply {
        store.add(1)
        store.add(2)
        store.add(3)
        store.queue.value = listOf(1)
    }.start()
    private val callback = SessionCallback(h.engine, h.scope)
    private val session = MediaSession.Builder(appContext(), h.engine.sessionPlayer).setId("queue-test").setCallback(callback).build()
    private val car = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "some.car", 0, 10_999,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        false, Bundle.EMPTY, true,
    )

    @After
    fun tearDown() {
        session.release()
        h.release()
    }

    private fun item(id: String, uri: String = "https://evil.example/$id.mp3") = MediaItem.Builder().setMediaId(id).setUri(uri).build()

    private fun ids() = (0 until h.player.mediaItemCount).map { h.player.getMediaItemAt(it).mediaId }

    /** What the session does with a controller's addMediaItems: resolve, then add to its player. */
    private fun controllerAdds(vararg items: MediaItem) {
        val future = callback.onAddMediaItems(session, car, items.toMutableList())
        idle()
        h.engine.sessionPlayer.addMediaItems(future.get())
        idle()
    }

    @Test
    fun anEpisodeAControllerAddsGoesIntoTheQueueAndThePlayerFollowsIt() {
        idle()
        assertEquals(listOf("1"), ids())
        controllerAdds(item("2"))
        assertEquals(listOf(1L, 2L), h.store.queue.value)
        assertEquals("added once, by the queue", listOf("1", "2"), ids())
        // Its URI was never used: the player's item is the library's.
        assertEquals("https://media.example.com/2.mp3", ItemSource.of(h.player.getMediaItemAt(1))!!.streamUrl)
    }

    @Test
    fun addingSurvivesARestartBecauseItIsInTheQueue() {
        controllerAdds(item("3"), item("404"), item("1"))
        assertEquals("unknown ids are dropped and a queued one stays where it is", listOf(1L, 3L), h.store.queue.value)
        val restarted = Harness(h.store).start()
        try {
            assertEquals(listOf("1", "3"), (0 until restarted.player.mediaItemCount).map { restarted.player.getMediaItemAt(it).mediaId })
        } finally {
            restarted.release()
        }
    }

    @Test
    fun aReplacingItemForAQueuedEpisodeResolvesToThatEpisode() {
        // replaceMediaItem(s) resolves through onAddMediaItems too; an empty answer would delete the item.
        val resolved = callback.onAddMediaItems(session, car, mutableListOf(item("1")))
        idle()
        assertEquals(listOf("1"), resolved.get().map { it.mediaId })
        assertEquals("https://media.example.com/1.mp3", ItemSource.of(resolved.get().single())!!.streamUrl)
        assertEquals("resolving changes no queue", listOf(1L), h.store.queue.value)
    }
}
