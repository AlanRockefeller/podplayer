/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.core.model.PositionChange

internal fun appContext(): Context = ApplicationProvider.getApplicationContext()

/** The player of the harness most recently built, if it's still alive. */
private var activePlayer: ExoPlayer? = null

/**
 * Lets queued work run: player commands, their callbacks and the coroutines they start on the main thread.
 * With a live player this waits for its pending commands rather than draining the looper: the fake clock
 * advances whenever the looper is idle, so a playing player would never let a full drain finish.
 */
internal fun idle() {
    val player = activePlayer
    if (player != null) {
        TestPlayerRunHelper.advance(player).untilPendingCommandsAreFullyHandled()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ZERO)
    } else {
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/** An in-memory [PlaybackStore]. Every write is recorded so tests can check when positions were saved. */
internal class FakePlaybackStore : PlaybackStore {
    val episodes = mutableMapOf<Long, PlayableEpisode>()
    val positions = mutableMapOf<Long, Long>()
    val played = mutableSetOf<Long>()
    val queue = MutableStateFlow<List<Long>>(emptyList())
    var current: Long? = null
    val settings = MutableStateFlow(PlaybackSettings())
    val showSpeeds = mutableMapOf<Long, Float>()
    val downloaded = MutableStateFlow<Set<Long>>(emptySet())
    val positionLog = mutableListOf<PositionChange>()
    val actions = mutableListOf<Pair<Long, EpisodeActionKind>>()
    val saves = mutableListOf<Pair<Long, Long>>()
    val logLines = mutableListOf<String>()

    /** Artwork [fetchArtwork] produces (a sandbox cache URI in the app), by episode id. */
    val fetchableArtwork = mutableMapOf<Long, Uri>()
    val artworkFetches = mutableListOf<Long>()

    /** Adds an episode; [file] makes it a completed download. [artworkUri] is artwork already cached. */
    fun add(
        id: Long,
        durationMs: Long = 600_000,
        feedId: Long = 1,
        file: File? = null,
        approvedHost: String? = null,
        artworkUri: Uri? = null,
    ) {
        episodes[id] = PlayableEpisode(
            id = id,
            feedId = feedId,
            title = "Episode $id",
            showTitle = "Show $feedId",
            streamUrl = "https://media.example.com/$id.mp3",
            localFile = file,
            approvedHost = approvedHost,
            artworkUri = artworkUri,
            durationMs = durationMs,
        )
        if (file != null) downloaded.value = downloaded.value + id
    }

    override suspend fun fetchArtwork(id: Long): Uri? {
        artworkFetches += id
        return fetchableArtwork[id]
    }

    override suspend fun episode(id: Long): PlayableEpisode? = episodes[id]?.let { ep ->
        if (id in downloaded.value) ep else ep.copy(localFile = null)
    }

    override suspend fun startPosition(id: Long): Long = if (id in played) 0 else positions[id] ?: 0

    override suspend fun savePosition(id: Long, positionMs: Long, durationMs: Long?) {
        positions[id] = positionMs
        saves += id to positionMs
    }

    override suspend fun markPlayed(id: Long) {
        played += id
    }

    override fun queue(): Flow<List<Long>> = queue

    override suspend fun putAtQueueHead(id: Long) {
        queue.value = listOf(id) + queue.value.filter { it != id }
    }

    override suspend fun removeFromQueue(id: Long) {
        queue.value = queue.value.filter { it != id }
    }

    override suspend fun currentEpisodeId(): Long? = current

    override suspend fun setCurrentEpisodeId(id: Long?) {
        current = id
    }

    override fun settings(): Flow<PlaybackSettings> = settings

    override suspend fun showSpeed(feedId: Long): Float? = showSpeeds[feedId]

    override suspend fun setShowSpeed(feedId: Long, speed: Float?) {
        if (speed == null) showSpeeds.remove(feedId) else showSpeeds[feedId] = speed
    }

    override suspend fun setGlobalSpeed(speed: Float) {
        settings.value = settings.value.copy(speed = PlaybackSettings.clampSpeed(speed))
    }

    override fun downloaded(): Flow<Set<Long>> = downloaded

    override suspend fun logPosition(change: PositionChange) {
        positionLog += change
    }

    override suspend fun appendAction(id: Long, kind: EpisodeActionKind, positionMs: Long?, durationMs: Long?) {
        actions += id to kind
    }

    override fun log(message: String) {
        logLines += message
    }
}

/**
 * Builds fake media for our items: a seekable window of the episode's length, carrying the item so the
 * player's current item keeps its id. Records which items were opened from a file and which streamed.
 */
internal class TestSources(private val store: FakePlaybackStore) : MediaSource.Factory {
    /** (episode id, opened from a file) for every source created. */
    val created = mutableListOf<Pair<Long, Boolean>>()

    /** Episode ids whose downloaded file fails to open. */
    val brokenFiles = mutableSetOf<Long>()

    /** Episode ids that stay buffering forever. */
    val stalled = mutableSetOf<Long>()

    /** Episode ids whose stream fails. */
    val brokenStreams = mutableSetOf<Long>()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = ItemSource.of(mediaItem) ?: error("an item without our tag reached the player")
        val id = source.episodeId
        val fromFile = source.file != null
        created += id to fromFile
        val durationMs = store.episodes[id]?.durationMs ?: 600_000
        val timeline = FakeTimeline(
            FakeTimeline.TimelineWindowDefinition.Builder()
                .setUid(mediaItem.mediaId + "/" + fromFile)
                .setDurationUs(durationMs * 1000)
                .setMediaItem(mediaItem)
                .build(),
        )
        val fails = (fromFile && id in brokenFiles) || (!fromFile && id in brokenStreams)
        return if (fails) {
            object : FakeMediaSource(timeline, ExoPlayerTestRunner.AUDIO_FORMAT) {
                override fun maybeThrowSourceInfoRefreshError() = throw IOException("broken source for $id")
            }.also { it.setAllowPreparation(false) }
        } else {
            FakeMediaSource(timeline, ExoPlayerTestRunner.AUDIO_FORMAT).also {
                if (id in stalled) it.setAllowPreparation(false)
                // Like the real progressive sources (GatedMediaSourceFactoryTest): new metadata for the same
                // audio updates the item in place.
                it.setCanUpdateMediaItems(true)
            }
        }
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(androidx.media3.common.C.CONTENT_TYPE_OTHER)
}

/** Renderers that consume samples without a codec, so playback runs under Robolectric. */
internal fun fakeRenderers(clock: Clock) = RenderersFactory { handler, _, audioListener, _, _ ->
    arrayOf(FakeAudioRenderer(clock.createHandler(handler.looper, null), audioListener))
}

/** A player built by the production [PlayerFactory], with fake media, renderers and clock. */
internal fun testPlayer(sources: MediaSource.Factory, clock: FakeClock = FakeClock(true)): ExoPlayer =
    PlayerFactory.create(appContext(), sources, clock, fakeRenderers(clock))

/** One engine over a fresh player, the way the service builds it. [release] simulates the service ending. */
internal class Harness(
    val store: FakePlaybackStore = FakePlaybackStore(),
    val clock: FakeClock = FakeClock(true),
) {
    val sources = TestSources(store)
    val player: ExoPlayer = testPlayer(sources, clock)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The audio buffers handed to the output, as the engine's silence watch sees them. The fake media holds a
     * single sample, so the real count would stop at once: null (unknown) unless a test sets it.
     */
    var renderedAudioBuffers: () -> Int? = { null }
    val engine = PlaybackEngine(player, store, scope, clock, renderedAudioBuffers = { renderedAudioBuffers() })

    init {
        activePlayer = player
    }

    fun start(): Harness {
        engine.start()
        idle()
        return this
    }

    /**
     * Plays until at least [positionMs], then pauses. Not exact: the fake clock advances by itself while a test
     * waits, so playback can run seconds past the target (and minutes pass in one step while paused). Tests read
     * the position after this rather than assume it.
     */
    fun pauseAfter(positionMs: Long) {
        player.play()
        TestPlayerRunHelper.run(player).untilPositionAtLeast(positionMs)
        player.pause()
        idle()
    }

    /** The process died: nothing is saved, the player just stops existing. */
    fun kill() {
        scope.cancel()
        player.release()
        if (activePlayer === player) activePlayer = null
    }

    fun release() {
        if (activePlayer !== player && activePlayer != null) activePlayer = player
        engine.release()
        idle()
        scope.cancel()
        player.release()
        if (activePlayer === player) activePlayer = null
    }
}
