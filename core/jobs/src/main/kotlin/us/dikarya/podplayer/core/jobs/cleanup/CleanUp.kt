/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.cleanup

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.CleanUpCandidate
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.jobs.download.DownloadRunner

/** Why clean-up deletes a download. */
sealed interface CleanUpReason {
    /** Played (98%, SPEC §10) at least [days] days ago. */
    data class Played(val days: Int) : CleanUpReason

    /** Not played, and downloaded at least [days] days ago. */
    data class Unplayed(val days: Int) : CleanUpReason

    /** For the download log (English, like the other log rows). */
    val message: String
        get() = when (this) {
            is Played -> "Deleted by clean-up: played ${days(days)} ago or more."
            is Unplayed -> "Deleted by clean-up: not played, downloaded ${days(days)} ago or more."
        }

    private fun days(n: Int) = if (n == 1) "1 day" else "$n days"
}

/** SPEC §9 clean-up, as one pure rule. */
object CleanUpRules {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Why [candidate] should be deleted at [now], or null to keep it. Never: starred, queued or loaded episodes,
     * a setting of 0 days, or a download whose times are missing or in the future (a clock that went back).
     */
    fun reason(candidate: CleanUpCandidate, settings: CleanUpSettings, loadedEpisodeId: Long?, now: Long): CleanUpReason? {
        if (candidate.starred || candidate.queued || candidate.episodeId == loadedEpisodeId) return null
        val clean = settings.normalized()
        return if (candidate.played) {
            val days = clean.playedDays
            val at = candidate.playedAt
            if (days == CleanUpSettings.NEVER || at == null || at > now || now - at < days * DAY_MS) null else CleanUpReason.Played(days)
        } else {
            val days = clean.unplayedDays
            val at = candidate.completedAt
            if (days == CleanUpSettings.NEVER || at == null || at > now || now - at < days * DAY_MS) null else CleanUpReason.Unplayed(days)
        }
    }
}

/**
 * Deletes downloads that [CleanUpRules] say are due (SPEC §9). Each deletion goes through
 * [DownloadRunner.cleanUp], which holds the episode's download lock and asks the rules again with the download as
 * it is then, so a download of the same episode that started meanwhile is never touched. Every deletion is in the
 * download log. [beforeEach] is a test seam: it runs after an episode is chosen, before its lock is taken.
 */
class CleanUp(
    private val repositories: suspend () -> Repositories?,
    private val runner: DownloadRunner,
    private val settings: suspend () -> CleanUpSettings,
    private val loadedEpisodeId: suspend () -> Long?,
    private val log: AppLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeEach: suspend (episodeId: Long) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Returns how many downloads were deleted. */
    suspend fun run(): Int = mutex.withLock {
        val repos = repositories() ?: return@withLock 0
        val rules = settings()
        val loaded = loadedEpisodeId()
        val due = repos.downloadRules.cleanUpCandidates().filter { CleanUpRules.reason(it, rules, loaded, clock()) != null }
        var deleted = 0
        for (candidate in due) {
            beforeEach(candidate.episodeId)
            try {
                val gone = runner.cleanUp(candidate.episodeId) { now ->
                    CleanUpRules.reason(now, settings(), loadedEpisodeId(), clock())?.message
                }
                if (gone) deleted++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "Clean-up of episode ${candidate.episodeId} failed", e)
            }
        }
        if (deleted > 0) log.i(TAG, "Clean-up deleted $deleted downloads")
        deleted
    }

    private companion object {
        const val TAG = "CleanUp"
    }
}
