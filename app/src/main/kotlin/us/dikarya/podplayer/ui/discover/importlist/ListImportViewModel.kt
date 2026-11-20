/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover.importlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.importing.ImportApplier
import us.dikarya.podplayer.core.data.importing.ImportEpisode
import us.dikarya.podplayer.core.data.importing.ImportFile
import us.dikarya.podplayer.core.data.importing.ImportFileProblem
import us.dikarya.podplayer.core.data.importing.ImportFileResult
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.redact
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.SubscribeOutcome
import us.dikarya.podplayer.ui.discover.DiscoverProblem
import us.dikarya.podplayer.ui.discover.PreviewOutcome
import us.dikarya.podplayer.ui.discover.SearchHit
import us.dikarya.podplayer.ui.discover.SearchOutcome

enum class RowStatus { Waiting, Searching, Matched, NoMatch, Failed }

/** One line of the list, with its matches. [label] is what the owner typed (addresses redacted). */
data class ImportRow(
    val key: Int,
    val label: String,
    val line: ImportLine,
    /** Marks from an import file, applied after subscribing. */
    val episodes: List<ImportEpisode> = emptyList(),
    val status: RowStatus = RowStatus.Waiting,
    val hits: List<SearchHit> = emptyList(),
    val chosen: Int? = null,
    val skipped: Boolean = false,
    val problem: DiscoverProblem? = null,
    /** Searches that failed in a row; resets on "Search again". */
    val failures: Int = 0,
    /** When a failed search is tried again on its own (the importer's clock); null: no retry pending. */
    val retryAt: Long? = null,
) {
    val chosenHit: SearchHit? get() = chosen?.let(hits::getOrNull)

    /** Subscribed on confirm. */
    val included: Boolean get() = !skipped && chosenHit != null
}

enum class ListImportProblem { Empty, TooMany, TooLarge, FileTooLarge, FileUnreadable, NotAnImportFile, NewerFile, Offline }

data class ListImportSummary(
    val added: Int,
    val alreadySubscribed: Int,
    val refused: Int,
    val skipped: Int,
    /** Lines with no match the owner could take. */
    val notFound: List<String>,
    /** Import-file episodes no library episode matched (M1-S9c reviews these with the owner). */
    val unmatchedEpisodes: List<String>,
    /** A write failed part-way, so the rows after it weren't imported; the counts say what was. */
    val stoppedByError: Boolean = false,
)

data class ListImportUiState(
    val text: String = "",
    val rows: List<ImportRow> = emptyList(),
    val matching: Boolean = false,
    val subscribing: Boolean = false,
    val problem: ListImportProblem? = null,
    val leftOut: ImportList? = null,
    val subscribedUrls: Map<String, Long> = emptyMap(),
    val summary: ListImportSummary? = null,
    val online: Boolean = true,
) {
    val toSubscribe: Int get() = rows.count { it.included }
}

/**
 * Discover › Import a list of shows (SPEC §12). Lines are untrusted. Names are matched one at a time through
 * Apple's search, paced ([Pacer]); addresses are never fetched before the owner confirms; nothing is subscribed
 * until [confirm], and every subscribe is `ownerTyped = false` (no private host is approved for a list).
 */
class ListImportViewModel(private val deps: BrowseDeps) : ViewModel() {
    private val local = MutableStateFlow(ListImportUiState())
    private val pacer = deps.importPacer()
    private var matcher: Job? = null

    val state: StateFlow<ListImportUiState> = combine(
        local,
        deps.online,
        deps.withRepositories<Map<String, Long>>(emptyMap()) { repos -> repos.feeds.feeds().map { feeds -> feeds.associate { it.url to it.id } } },
    ) { state, online, subscribed -> state.copy(online = online, subscribedUrls = subscribed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListImportUiState())

    init {
        takeSharedText()
    }

    /** Text shared into the app waits in [BrowseDeps.sharedText]; it is taken once. */
    fun takeSharedText() {
        val shared = deps.sharedText.value ?: return
        deps.sharedText.value = null
        local.update { ListImportUiState(text = shared.take(ImportLines.MAX_CHARS + 1)) }
    }

    fun onTextChange(text: String) = local.update { it.copy(text = text, problem = null) }

    /** Splits the text into lines and starts matching them. */
    fun findShows() {
        val text = local.value.text
        if (ImportFile.looksLikeOne(text)) return readFile(text.toByteArray())
        when (val result = ImportLines.parse(text)) {
            ImportLinesResult.Empty -> fail(ListImportProblem.Empty)
            ImportLinesResult.TooMany -> fail(ListImportProblem.TooMany)
            ImportLinesResult.TooLarge -> fail(ListImportProblem.TooLarge)
            is ImportLinesResult.Ok -> start(result.list.lines.map { it to emptyList() }, result.list)
        }
    }

    /** A text or import file the owner picked or shared: read under the import file's size limit. */
    fun filePicked(open: () -> InputStream?) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { readLimited(open) }
            when {
                bytes == null -> fail(ListImportProblem.FileUnreadable)
                bytes === TOO_LARGE -> fail(ListImportProblem.FileTooLarge)
                else -> {
                    val text = bytes.decodeToString()
                    if (ImportFile.looksLikeOne(text)) {
                        readFile(bytes)
                    } else {
                        local.update { it.copy(text = text.take(ImportLines.MAX_CHARS + 1)) }
                        findShows()
                    }
                }
            }
        }
    }

    private fun readFile(bytes: ByteArray) {
        when (val result = ImportFile.parse(bytes)) {
            is ImportFileResult.Refused -> fail(
                when (result.problem) {
                    ImportFileProblem.TooLarge -> ListImportProblem.FileTooLarge
                    ImportFileProblem.NotAnImportFile -> ListImportProblem.NotAnImportFile
                    ImportFileProblem.NewerVersion -> ListImportProblem.NewerFile
                    ImportFileProblem.TooManyItems -> ListImportProblem.TooMany
                },
            )
            is ImportFileResult.Ok -> {
                val lines = result.shows.map { show ->
                    val line = show.feedUrl?.let { ImportLine.Address(it) } ?: ImportLine.Name(show.title.orEmpty())
                    line to show.episodes
                }
                if (lines.isEmpty()) fail(ListImportProblem.Empty) else start(lines, ImportList(emptyList(), refused = result.dropped))
            }
        }
    }

    private fun fail(problem: ListImportProblem) = local.update { it.copy(problem = problem) }

    private fun start(lines: List<Pair<ImportLine, List<ImportEpisode>>>, leftOut: ImportList) {
        val rows = lines.mapIndexed { i, (line, episodes) ->
            when (line) {
                is ImportLine.Address -> {
                    val hit = SearchHit(feedUrl = line.url, title = redact(line.url), publisher = null, artworkUrl = null)
                    ImportRow(i, redact(line.url), line, episodes, RowStatus.Matched, listOf(hit), chosen = 0)
                }
                is ImportLine.Name -> ImportRow(i, line.text, line, episodes)
                is ImportLine.AppleId -> ImportRow(i, "podcasts.apple.com/id${line.id}", line, episodes)
            }
        }
        // A matcher still searching the previous list must not write its results into these rows (keys repeat).
        matcher?.cancel()
        local.update { it.copy(rows = rows, leftOut = leftOut, problem = null, summary = null) }
        matchWaiting(replace = true)
    }

    /** True while the matcher only waits for a failed row's retry time, so "Search again" may wake it. */
    @Volatile
    private var waitingForRetry = false

    /**
     * Matches every waiting row in order, one paced request at a time. A failed search is tried again on its own
     * after each of [RETRY_WAITS_MS] (other rows go on meanwhile; a retry that is due goes first), then the row
     * keeps "Search again". [restart] replaces a matcher that is only waiting for a retry; [replace] replaces any
     * (a new list).
     */
    private fun matchWaiting(restart: Boolean = false, replace: Boolean = false) {
        val previous = matcher
        if (previous?.isActive == true && !replace && !(restart && waitingForRetry)) return
        local.update { it.copy(matching = true) }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
            try {
                matchLoop()
            } finally {
                // A replaced matcher leaves `matching` to the one that took over.
                if (matcher === job) local.update { it.copy(matching = false) }
            }
        }
        matcher = job
        job.start()
    }

    private suspend fun matchLoop() {
        while (true) {
            val rows = local.value.rows
            val now = pacer.now()
            val row = rows.firstOrNull { it.retryAt != null && it.retryAt <= now && !it.skipped }
                ?: rows.firstOrNull { it.status == RowStatus.Waiting }
            if (row == null) {
                val next = rows.filter { !it.skipped }.mapNotNull { it.retryAt }.minOrNull() ?: return
                waitingForRetry = true
                try {
                    pacer.waitUntil(next)
                } finally {
                    waitingForRetry = false
                }
                continue
            }
            if (!deps.online.value) {
                failed(row.key, DiscoverProblem.Offline)
                continue
            }
            pacer.await()
            setRow(row.key) { it.copy(status = RowStatus.Searching, retryAt = null) }
            val outcome = when (val line = row.line) {
                is ImportLine.Name -> deps.search.search(line.text)
                is ImportLine.AppleId -> deps.search.lookup(line.id)
                is ImportLine.Address -> continue
            }
            // A search that finished after this matcher was replaced belongs to rows that are gone.
            currentCoroutineContext().ensureActive()
            when (outcome) {
                is SearchOutcome.Results -> {
                    pacer.relax()
                    val hits = outcome.hits.take(MAX_HITS)
                    setRow(row.key) {
                        val done = it.copy(failures = 0, retryAt = null, problem = null)
                        if (hits.isEmpty()) done.copy(status = RowStatus.NoMatch) else done.copy(status = RowStatus.Matched, hits = hits, chosen = 0)
                    }
                }
                is SearchOutcome.Failed -> {
                    val code = ((outcome.problem as? DiscoverProblem.Net)?.error as? NetError.HttpStatus)?.code
                    // A retry that is throttled again doesn't slow the whole list down further.
                    if ((code == TOO_MANY_REQUESTS || code == FORBIDDEN) && row.failures == 0) pacer.slowDown()
                    failed(row.key, outcome.problem)
                }
            }
        }
    }

    /** Marks a row failed and, unless its retries are used up, when it is tried again. */
    private fun failed(key: Int, problem: DiscoverProblem) {
        val now = pacer.now()
        setRow(key) {
            val failures = it.failures + 1
            val wait = RETRY_WAITS_MS.getOrNull(failures - 1)
            it.copy(status = RowStatus.Failed, problem = problem, failures = failures, retryAt = wait?.let { w -> now + w })
        }
    }

    private fun setRow(key: Int, change: (ImportRow) -> ImportRow) =
        local.update { s -> s.copy(rows = s.rows.map { if (it.key == key) change(it) else it }) }

    /** The owner's change to a row; none while subscribing, since [confirm] works on the rows as confirmed. */
    private fun edit(key: Int, change: (ImportRow) -> ImportRow): Boolean {
        if (local.value.subscribing) return false
        setRow(key, change)
        return true
    }

    /** Takes another of the row's matches. */
    fun choose(key: Int, hit: Int) {
        edit(key) { if (hit in it.hits.indices) it.copy(chosen = hit, skipped = false) else it }
    }

    fun skip(key: Int) {
        edit(key) { it.copy(skipped = true) }
    }

    fun include(key: Int) {
        edit(key) { it.copy(skipped = false) }
    }

    /** Searches a failed or unmatched row again. */
    fun retry(key: Int) {
        val edited = edit(key) {
            if (it.status == RowStatus.Failed || it.status == RowStatus.NoMatch) it.copy(status = RowStatus.Waiting, problem = null, failures = 0, retryAt = null) else it
        }
        if (edited) matchWaiting(restart = true)
    }

    /** Back to the text field, keeping the text. */
    fun startOver() {
        matcher?.cancel()
        local.update { ListImportUiState(text = it.text) }
    }

    /** Subscribes to every included row; the only step that fetches feeds (and only for rows with marks). */
    fun confirm() {
        val current = local.value
        if (current.subscribing || current.summary != null) return
        matcher?.cancel()
        local.update { it.copy(subscribing = true, matching = false) }
        viewModelScope.launch {
            var added = 0
            var already = 0
            var refused = 0
            val unmatched = ArrayList<String>()
            val rows = local.value.rows
            var stopped = false
            try {
                for (row in rows.filter { it.included }) {
                    val url = row.chosenHit!!.feedUrl
                    // Marks need the show's episodes: fetched for a new show, and for one subscribed but not yet refreshed.
                    val existingId = state.value.subscribedUrls[url]
                    val needsEpisodes = row.episodes.isNotEmpty() &&
                        (existingId == null || deps.repositories()?.episodes?.forFeed(existingId)?.first().isNullOrEmpty())
                    val preview = if (needsEpisodes) {
                        (deps.previewer.preview(url, ownerTyped = false) as? PreviewOutcome.Ready)?.preview?.feed
                    } else {
                        null
                    }
                    val feedId = when (val outcome = deps.subscriptions.subscribe(url, ownerTyped = false, preview = preview)) {
                        is SubscribeOutcome.Subscribed -> outcome.feedId.also { added++ }
                        is SubscribeOutcome.AlreadySubscribed -> outcome.feedId.also {
                            already++
                            // Same parser and limits as a refresh, as when subscribing with a preview.
                            if (preview != null) deps.repositories()?.feeds?.applyRefresh(it, preview)
                        }
                        is SubscribeOutcome.Refused, SubscribeOutcome.LibraryClosed -> null.also { refused++ }
                    }
                    if (feedId != null && row.episodes.isNotEmpty()) {
                        val repos = deps.repositories() ?: continue
                        val report = ImportApplier.apply(repos, feedId, row.episodes)
                        unmatched += report.unmatched
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed write (storage full, say). What was subscribed stays; importing again is safe. Only the
                // exception's type is logged: its message could quote a feed address (a secret).
                deps.log.w(TAG, "Import stopped: a write failed (${e.javaClass.simpleName})")
                stopped = true
            }
            val summary = ListImportSummary(
                added = added,
                alreadySubscribed = already,
                refused = refused,
                skipped = rows.count { it.skipped },
                notFound = rows.filter { !it.skipped && it.chosenHit == null }.map { it.label },
                unmatchedEpisodes = unmatched,
                stoppedByError = stopped,
            )
            deps.log.i(TAG, "Import: ${summary.added} added, ${summary.alreadySubscribed} already there, ${summary.refused} refused")
            local.update { it.copy(subscribing = false, summary = summary) }
        }
    }

    private fun readLimited(open: () -> InputStream?): ByteArray? = try {
        open()?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > ImportFile.MAX_BYTES) return TOO_LARGE
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** How long a failed search waits before each automatic retry; after the last one it gives up. */
        val RETRY_WAITS_MS: List<Long> = listOf(10_000L, 30_000L, 90_000L)

        private const val MAX_HITS = 5
        private const val TOO_MANY_REQUESTS = 429
        private const val FORBIDDEN = 403
        private const val BUFFER = 64 * 1024
        private const val TAG = "Import"

        /** A marker compared by identity: the file was over the limit. */
        private val TOO_LARGE = ByteArray(0)
    }
}
