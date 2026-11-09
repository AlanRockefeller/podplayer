/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.media.MediaPaths
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.jobs.Messages
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.NetLimits
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.download.DownloadRequest
import us.dikarya.podplayer.core.net.download.DownloadResult
import us.dikarya.podplayer.core.net.download.DownloadSink
import us.dikarya.podplayer.core.net.download.FileDownloadSink
import us.dikarya.podplayer.core.net.download.RangeDownloader

sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome

    data object AlreadyComplete : DownloadOutcome

    /** The episode, or its queued download, no longer exists. Nothing to do. */
    data object Gone : DownloadOutcome

    /** Worth trying again later (network, server trouble); the partial file is kept. */
    data class Retry(val message: String) : DownloadOutcome

    /** Trying again won't help without a change (refused address, not audio, storage limit, disk full). */
    data class Failed(val message: String) : DownloadOutcome
}

/** Why a running download was stopped, for the log. */
enum class StopCause {
    /** The owner cancelled it. */
    Owner,

    /** Android 15+ ended the foreground service after its daily `dataSync` allowance (`onTimeout`). */
    ForegroundTimeout,

    /** Anything else: constraints no longer met, the system needed resources, the app was updated. */
    System,
}

/** One download, as the worker runs it; [DownloadRunner] in the app, a fake in worker tests. */
fun interface DownloadTask {
    suspend fun run(episodeId: Long, onProgress: (bytes: Long, total: Long?) -> Unit, stopCause: () -> StopCause): DownloadOutcome

    /** The worker stopped retrying. The partial file stays, so downloading again resumes it. */
    suspend fun gaveUp(episodeId: Long, attempts: Int) = Unit
}

/** A mutex per key: a download and a delete of the same episode never overlap. */
class KeyedLocks {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(key: Long, block: suspend () -> T): T = locks.getOrPut(key) { Mutex() }.withLock { block() }
}

/**
 * Downloads one episode (SPEC §9, LESSONS "Downloads"): through the gated engine with the feed's address
 * policy, into a generated path in app-private storage, resuming a partial file with `Range` + `If-Range`.
 * The storage cap is the engine's byte limit. A finished transfer only becomes [DownloadState.Complete] when
 * the Media3 probe recognises audio and reads its duration; the declared length and MIME type are ignored.
 * Every step goes into the download log, redacted.
 */
class DownloadRunner(
    private val repositories: suspend () -> Repositories?,
    private val paths: MediaPaths,
    private val storageCap: suspend () -> Long,
    private val engine: RangeDownloader,
    private val probe: AudioProbe,
    private val log: AppLog,
    private val locks: KeyedLocks,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sinkFactory: (File) -> DownloadSink = ::FileDownloadSink,
) : DownloadTask {

    /**
     * Records that the owner wants this episode downloaded. Returns false when there is nothing to queue:
     * an unknown episode, or a download that is already complete.
     */
    suspend fun queue(episodeId: Long): Boolean = locks.withLock(episodeId) {
        val repos = repositories() ?: return@withLock false
        val episode = repos.episodes.get(episodeId) ?: return@withLock false
        val existing = repos.media.get(episodeId)
        if (existing?.state == DownloadState.Complete) return@withLock false
        // Already waiting or running: leave its record alone (the unique work keeps the one in progress).
        if (existing?.state == DownloadState.Queued || existing?.state == DownloadState.Downloading) return@withLock true
        val path = existing?.relativePath ?: paths.relativePathFor(episode.feedId, episode.id, extensionHint(episode))
        repos.media.upsert(
            MediaFile(episodeId, path, DownloadState.Queued, existing?.bytesDownloaded ?: 0, existing?.totalBytes, null, null),
            clock(),
        )
        repos.logs.addDownload(episodeId, DownloadEvent.Queued, null)
        true
    }

    override suspend fun run(episodeId: Long, onProgress: (Long, Long?) -> Unit, stopCause: () -> StopCause): DownloadOutcome =
        locks.withLock(episodeId) {
            val repos = repositories() ?: return@withLock DownloadOutcome.Retry("The library isn't open.")
            val episode = repos.episodes.get(episodeId) ?: return@withLock DownloadOutcome.Gone
            val record = repos.media.get(episodeId) ?: return@withLock DownloadOutcome.Gone
            if (record.state == DownloadState.Complete) return@withLock DownloadOutcome.AlreadyComplete
            val file = paths.resolve(record.relativePath)
                ?: return@withLock fail(repos, episodeId, record, null, "The download's file name isn't safe, so it was refused.")
            Attempt(repos, episode, record, file, onProgress, stopCause).run()
        }

    suspend fun run(episodeId: Long): DownloadOutcome = run(episodeId, { _, _ -> }, { StopCause.System })

    override suspend fun gaveUp(episodeId: Long, attempts: Int) = locks.withLock(episodeId) {
        val repos = repositories() ?: return@withLock
        val record = repos.media.get(episodeId)?.takeIf { it.state != DownloadState.Complete } ?: return@withLock
        repos.media.upsert(record.copy(state = DownloadState.Failed), clock())
        repos.logs.addDownload(episodeId, DownloadEvent.Failed, "Gave up after $attempts attempts. Download again to resume.")
    }

    /** Removes the file and the record (SPEC §9 "delete"). Waits for a running download of it to stop first. */
    suspend fun delete(episodeId: Long) = locks.withLock(episodeId) {
        val repos = repositories() ?: return@withLock
        val record = repos.media.get(episodeId) ?: return@withLock
        withContext(Dispatchers.IO) { paths.resolve(record.relativePath)?.delete() }
        repos.media.delete(episodeId)
        repos.logs.addDownload(episodeId, DownloadEvent.Deleted, null)
        if (record.state == DownloadState.Complete) repos.actions.append(episodeId, EpisodeActionKind.Deleted, null, null)
    }

    private inner class Attempt(
        val repos: Repositories,
        val episode: Episode,
        val record: MediaFile,
        val file: File,
        val onProgress: (Long, Long?) -> Unit,
        val stopCause: () -> StopCause,
    ) {
        val id = episode.id
        val bytesOnDisk = AtomicLong(0)
        val totalBytes = AtomicReference<Long?>(record.totalBytes)

        suspend fun run(): DownloadOutcome {
            val url = when (val check = UrlPolicy.checkFeedUrl(episode.enclosure.url)) {
                is UrlCheck.Allowed -> check.url
                is UrlCheck.Rejected -> return fail(repos, id, record, file, "The episode's address isn't one this app can use (${check.problem.name}).")
            }
            val feed = repos.feeds.get(episode.feedId) ?: return DownloadOutcome.Gone
            val policy = feed.ownerApprovedHost?.let { AddressPolicy.approvingHosts(it) } ?: AddressPolicy.Default

            val cap = storageCap()
            val budget = cap - repos.media.usedBytes(exceptEpisodeId = id)
            val partial = withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                if (file.isFile) file.length() else 0L
            }
            if (budget <= 0 || budget < partial) return fail(repos, id, record, file, Messages.storageCap(cap))

            val validators = repos.media.validators(id).takeUnless { it.isEmpty }
            save(DownloadState.Downloading, partial, record.totalBytes)
            if (partial > 0 && validators != null) {
                repos.logs.addDownload(id, DownloadEvent.Resumed, "Resuming from ${Messages.mib(partial)}.")
            } else {
                repos.logs.addDownload(id, DownloadEvent.Started, null)
            }

            val request = DownloadRequest(url, policy, validators, maxBytes = budget.coerceAtMost(NetLimits.MEDIA_MAX_BYTES))
            bytesOnDisk.set(partial)
            val result = try {
                coroutineScope {
                    // The database shows progress too, a few times a second at most.
                    val saver = launch {
                        while (true) {
                            delay(PROGRESS_SAVE_MS)
                            save(DownloadState.Downloading, bytesOnDisk.get(), totalBytes.get())
                        }
                    }
                    try {
                        engine.download(request, sinkFactory(file)) { bytes, total ->
                            bytesOnDisk.set(bytes)
                            if (total != null) totalBytes.set(total)
                            onProgress(bytes, total)
                        }
                    } finally {
                        saver.cancel()
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stopped() }
                throw e
            }
            return when (result) {
                is DownloadResult.Complete -> finished(result, url)
                is DownloadResult.Failed -> failed(result, cap)
            }
        }

        private suspend fun finished(result: DownloadResult.Complete, url: HttpUrl): DownloadOutcome {
            repos.media.saveValidators(id, result.validators)
            result.restart?.let { repos.logs.addDownload(id, DownloadEvent.Restarted, Messages.restart(it)) }
            return when (val probed = withContext(Dispatchers.IO) { probe.probe(file) }) {
                is ProbeResult.Rejected -> fail(repos, id, record, file, Messages.probe(probed.problem))
                is ProbeResult.Audio -> {
                    val now = clock()
                    repos.media.markComplete(id, result.bytes, probed.durationMs, probed.mimeType, now)
                    repos.logs.addDownload(
                        id,
                        DownloadEvent.Completed,
                        "${Messages.mib(result.bytes)}, ${minutes(probed.durationMs)} of ${probed.mimeType ?: "audio"}" +
                            (if (result.redirects.hops.isEmpty()) "" else ", after ${result.redirects.hops.size} redirects") +
                            " from ${url.host}.",
                    )
                    repos.actions.append(id, EpisodeActionKind.Downloaded, null, probed.durationMs)
                    log.i(TAG, "Episode $id downloaded: ${result.bytes} bytes, ${probed.durationMs} ms")
                    DownloadOutcome.Completed
                }
            }
        }

        private suspend fun failed(result: DownloadResult.Failed, cap: Long): DownloadOutcome {
            result.restart?.let { repos.logs.addDownload(id, DownloadEvent.Restarted, Messages.restart(it)) }
            val error = result.error
            val message = when {
                error is NetError.TooLarge && error.limitBytes < NetLimits.MEDIA_MAX_BYTES -> Messages.storageCap(cap)
                else -> Messages.net(error)
            }
            val permanent = when (error) {
                is NetError.Storage, is NetError.TooLarge, is NetError.BlockedAddress, is NetError.InvalidUrl,
                is NetError.BadRedirect, is NetError.TooManyRedirects -> true
                is NetError.HttpStatus -> error.code in 400..499 && error.code != 408 && error.code != 429
                NetError.TimedOut, NetError.Stalled, is NetError.Network -> false
            }
            // Retrying won't finish it, so the partial file only takes space (a full disk most of all).
            if (permanent) return fail(repos, id, record, file, message)
            repos.media.saveValidators(id, result.validators)
            save(DownloadState.Paused, result.bytesOnDisk, totalBytes.get())
            repos.logs.addDownload(id, DownloadEvent.Failed, "$message It will be tried again.")
            log.w(TAG, "Episode $id download interrupted: $message")
            return DownloadOutcome.Retry(message)
        }

        private suspend fun stopped() {
            val bytes = if (file.isFile) file.length() else 0L
            save(DownloadState.Paused, bytes, totalBytes.get())
            val why = when (stopCause()) {
                StopCause.Owner -> "You stopped the download."
                StopCause.ForegroundTimeout ->
                    "Android's 6-hour daily limit for foreground downloads ran out; the download continues later in the background."
                StopCause.System -> "Android stopped the download for now; it continues later."
            }
            repos.logs.addDownload(id, DownloadEvent.Cancelled, "$why ${Messages.mib(bytes)} kept.")
        }

        private suspend fun save(state: DownloadState, bytes: Long, total: Long?) {
            repos.media.upsert(MediaFile(id, record.relativePath, state, bytes, total, null, null), clock())
        }
    }

    /** Marks the download failed and deletes [file] (null when its path was unsafe), so it takes no space. */
    private suspend fun fail(repos: Repositories, id: Long, record: MediaFile, file: File?, message: String): DownloadOutcome {
        if (file != null) withContext(Dispatchers.IO) { file.delete() }
        repos.media.upsert(MediaFile(id, record.relativePath, DownloadState.Failed, 0, record.totalBytes, null, null), clock())
        repos.logs.addDownload(id, DownloadEvent.Failed, message)
        log.w(TAG, "Episode $id download failed: $message")
        return DownloadOutcome.Failed(message)
    }

    private fun minutes(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(java.util.Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
    }

    internal companion object {
        const val TAG = "Download"
        const val PROGRESS_SAVE_MS = 2_000L

        private val typeExtensions = mapOf(
            "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mpeg3" to "mp3", "audio/x-mpeg" to "mp3",
            "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a", "audio/aac" to "aac",
            "audio/x-aac" to "aac", "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/flac" to "flac",
            "audio/x-flac" to "flac", "audio/wav" to "wav", "audio/x-wav" to "wav",
        )

        /** A hint only (SPEC §9: declared types are often wrong); playback sniffs the content anyway. */
        fun extensionHint(episode: Episode): String? {
            typeExtensions[episode.enclosure.type?.lowercase()?.substringBefore(';')?.trim()]?.let { return it }
            val path = UrlPolicy.checkFeedUrl(episode.enclosure.url).let { (it as? UrlCheck.Allowed)?.url?.encodedPath }
            return path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in MediaPaths.EXTENSIONS }
        }
    }
}
