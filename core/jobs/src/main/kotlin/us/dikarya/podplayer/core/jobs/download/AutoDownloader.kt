/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.data.settings.DeviceSettings
import us.dikarya.podplayer.core.jobs.Messages
import us.dikarya.podplayer.core.jobs.refresh.RefreshRequest
import us.dikarya.podplayer.core.jobs.refresh.RefreshSummary
import us.dikarya.podplayer.core.jobs.refresh.Refresher
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.net.NetLimits

/** What one auto-download pass did. */
data class AutoDownloadResult(val queued: Int, val stoppedAtCap: Boolean)

/**
 * SPEC §9 "Auto-download": after each refresh, queues the new episodes of shows with "Download new episodes" on
 * (only those published after it was turned on), and, with "keep starred episodes downloaded", starred episodes
 * that aren't downloaded. Each episode is queued automatically at most once. They are background downloads on the
 * auto-download network setting ([enqueue] passes `auto = true`).
 *
 * The storage cap: once the downloads on disk plus the declared sizes of what this pass queued would pass it, the
 * pass stops, notes the time for Settings and says so in the app log (once until it clears). Nothing is ever
 * deleted to make room. The runner still enforces the cap on the real bytes of every download.
 */
class AutoDownloader(
    private val repositories: suspend () -> Repositories?,
    private val enqueue: suspend (episodeId: Long) -> Boolean,
    private val storageCap: suspend () -> Long,
    private val cleanUp: suspend () -> CleanUpSettings,
    private val device: DeviceSettings,
    private val log: AppLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun run(): AutoDownloadResult = mutex.withLock {
        val repos = repositories() ?: return@withLock AutoDownloadResult(0, false)
        val candidates = buildList {
            addAll(repos.downloadRules.autoDownloadCandidates(MAX_PER_RUN))
            if (cleanUp().keepStarredDownloaded) addAll(repos.downloadRules.starredNotDownloaded(MAX_PER_RUN))
        }.distinctBy { it.id }
        val cap = storageCap()
        var planned = repos.media.usedBytes(exceptEpisodeId = NO_EPISODE)
        var queued = 0
        var stopped = false
        for (episode in candidates) {
            val size = declaredSize(episode)
            if (planned >= cap || planned + size > cap) {
                stopped = true
                break
            }
            // Marked first: if queuing fails, it is not retried automatically on every refresh.
            repos.downloadRules.markAutoQueued(episode.id)
            if (enqueue(episode.id)) {
                queued++
                planned += size
            }
        }
        val wasStopped = device.autoDownloadStoppedAt.first() != null
        if (stopped) {
            device.setAutoDownloadStoppedAt(clock())
            if (!wasStopped) {
                log.w(TAG, "Auto-downloads stopped: downloads reached the storage limit of ${Messages.mib(cap)}. Nothing was deleted to make room.")
            }
        } else if (wasStopped) {
            device.setAutoDownloadStoppedAt(null)
            log.i(TAG, "Auto-downloads are running again: there is room under the storage limit.")
        }
        if (queued > 0) log.i(TAG, "Auto-download queued $queued episodes")
        AutoDownloadResult(queued, stopped)
    }

    /** The feed's length is a hint: missing, negative or absurd (over the media limit) counts as unknown. */
    private fun declaredSize(episode: Episode): Long =
        episode.enclosure.length?.takeIf { it in 1..NetLimits.MEDIA_MAX_BYTES } ?: 0L

    private companion object {
        const val TAG = "AutoDownload"

        /** Bounds one pass, whatever a feed claims about its dates. */
        const val MAX_PER_RUN = 50

        /** Episode ids start at 1, so this counts every download. */
        const val NO_EPISODE = 0L
    }
}

/**
 * Every refresh is followed by an auto-download pass (SPEC §9 "runs after each refresh"). A failing pass goes to
 * [onError] and never fails the refresh.
 */
class AutoDownloadAfterRefresh(
    private val refresher: Refresher,
    private val onError: (Exception) -> Unit = {},
    private val after: suspend () -> Unit,
) : Refresher {
    override suspend fun refresh(request: RefreshRequest): RefreshSummary {
        val summary = refresher.refresh(request)
        try {
            after()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
        return summary
    }
}
