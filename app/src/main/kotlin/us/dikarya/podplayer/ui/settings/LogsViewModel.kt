/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.db.DownloadEvent
import us.dikarya.podplayer.core.data.db.RefreshOutcome
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.model.PositionCause

enum class LogTab { App, Playback, Downloads, Refresh, Crashes }

/**
 * One line of a log as the viewer shows it. [subject] is the episode, show or crash file it is about; the typed
 * fields let the screen use `strings.xml` for what the database stores as codes.
 */
data class LogEntry(
    val at: Long?,
    val text: String,
    val subject: String? = null,
    val cause: PositionCause? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val streaming: Boolean? = null,
    val downloadEvent: DownloadEvent? = null,
    val refreshOutcome: RefreshOutcome? = null,
    val newEpisodes: Int = 0,
    val updatedEpisodes: Int = 0,
)

sealed interface ExportState {
    data object Idle : ExportState

    data object Working : ExportState

    /** Written; the screen opens the share sheet for it, then calls [LogsViewModel.exportShared]. */
    data class Ready(val file: File) : ExportState

    data object Failed : ExportState
}

data class LogsUiState(
    val tab: LogTab = LogTab.App,
    val loading: Boolean = true,
    val entries: List<LogEntry> = emptyList(),
    val export: ExportState = ExportState.Idle,
)

/**
 * Settings › Logs (SPEC §12, §13). Everything shown is already redacted by the log writers and redacted again on
 * reading. The export file is written only by [export], which only the Export button calls; nothing leaves the
 * phone unless the owner picks an app in the share sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModel(private val deps: SettingsDeps) : ViewModel() {
    private val tab = MutableStateFlow(LogTab.App)
    private val export = MutableStateFlow<ExportState>(ExportState.Idle)

    private class Shown(val tab: LogTab, val loading: Boolean, val entries: List<LogEntry>)

    private val shown: Flow<Shown> = tab.flatMapLatest { t ->
        entries(t).map { Shown(t, false, it) }.onStart { emit(Shown(t, true, emptyList())) }
    }

    val state: StateFlow<LogsUiState> = combine(shown, export) { s, e -> LogsUiState(s.tab, s.loading, s.entries, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), LogsUiState())

    fun select(newTab: LogTab) = tab.update { newTab }

    private fun entries(tab: LogTab): Flow<List<LogEntry>> = when (tab) {
        LogTab.App -> flow { emit(appEntries().asReversed()) }.flowOn(Dispatchers.IO)
        LogTab.Crashes -> flow {
            emit(deps.logs.crashes().map { LogEntry(at = null, text = it.text, subject = it.name) })
        }.flowOn(Dispatchers.IO)
        LogTab.Playback -> withRepositories { repos ->
            val playbackLines = flow { emit(appEntries().filter { it.subject == PLAYBACK_TAG }) }.flowOn(Dispatchers.IO)
            combine(playbackLines, repos.logs.positionLog(ROWS)) { lines, changes ->
                val titles = episodeTitles(repos, changes.map { it.episodeId })
                val positions = changes.map { c ->
                    LogEntry(
                        at = c.at,
                        text = c.cause.name,
                        subject = titles[c.episodeId],
                        cause = c.cause,
                        fromMs = c.fromMs,
                        toMs = c.toMs,
                        streaming = c.streaming,
                    )
                }
                (lines + positions).sortedByDescending { it.at ?: 0 }
            }
        }
        LogTab.Downloads -> withRepositories { repos ->
            repos.logs.downloadLog(ROWS).map { rows ->
                val titles = episodeTitles(repos, rows.map { it.episodeId })
                rows.map { LogEntry(at = it.at, text = it.message.orEmpty(), subject = titles[it.episodeId], downloadEvent = it.event) }
            }
        }
        LogTab.Refresh -> withRepositories { repos ->
            repos.logs.refreshLog(ROWS).map { rows ->
                val titles = HashMap<Long, String?>()
                for (id in rows.map { it.feedId }.distinct()) titles[id] = repos.feeds.get(id)?.title
                rows.map {
                    LogEntry(
                        at = it.at,
                        text = it.message.orEmpty(),
                        subject = titles[it.feedId],
                        refreshOutcome = it.outcome,
                        newEpisodes = it.newEpisodes,
                        updatedEpisodes = it.updatedEpisodes,
                    )
                }
            }
        }
    }

    private fun withRepositories(block: (Repositories) -> Flow<List<LogEntry>>): Flow<List<LogEntry>> =
        flow { emit(deps.repositories()) }.flatMapLatest { repos -> if (repos == null) flowOf(emptyList()) else block(repos) }

    private suspend fun episodeTitles(repos: Repositories, ids: List<Long>): Map<Long, String?> {
        val titles = HashMap<Long, String?>()
        for (id in ids.distinct()) titles[id] = repos.episodes.get(id)?.title
        return titles
    }

    /** The app log's lines, oldest first, with their time and tag split out when the line has them. */
    private suspend fun appEntries(): List<LogEntry> = groupAppLines(deps.logs.appLines()).map(::parseAppLine)

    /** Starts the export. Only the Export button calls this (SPEC §13: export only when the owner taps Export). */
    fun export() {
        if (export.value == ExportState.Working) return
        export.value = ExportState.Working
        viewModelScope.launch {
            export.value = try {
                ExportState.Ready(writeExport())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log.w(TAG, "The log export couldn't be written", e)
                ExportState.Failed
            }
        }
    }

    /** The share sheet was opened (or couldn't be); the file stays until the next export replaces it. */
    fun exportShared() {
        export.value = ExportState.Idle
    }

    private suspend fun writeExport(): File = withContext(Dispatchers.IO) {
        val dir = deps.exportDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create the export folder")
        // One export at a time on the phone: an older one is removed rather than kept around.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "podplayer-logs-${fileStamp.format(Instant.now())}.txt")
        file.outputStream().use { deps.logs.writeTo(it) }
        deps.log.i(TAG, "Logs exported for sharing")
        file
    }

    companion object {
        private const val STOP_AFTER_MS = 5_000L
        private const val TAG = "Logs"
        private const val ROWS = 300

        /** The tag `RoomPlaybackStore` logs under: "Ignored Play from …", file fallbacks and the like. */
        const val PLAYBACK_TAG = "Playback"

        private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        /** How `AppLog` indents the lines after a record's first (a message's line breaks, a stack trace). */
        private const val CONTINUATION = "    "

        /**
         * Joins each record's continuation lines to its first line, so a stack trace stays with its message
         * (CodeRabbit review, PR #1). A continuation with no record before it (cut off by the line limit) stands alone.
         */
        internal fun groupAppLines(lines: List<String>): List<String> {
            val records = ArrayList<StringBuilder>()
            for (line in lines) {
                if (line.startsWith(CONTINUATION) && records.isNotEmpty()) {
                    records.last().append('\n').append(line, CONTINUATION.length, line.length)
                } else {
                    records += StringBuilder(line)
                }
            }
            return records.map { it.toString() }
        }

        /** "2026-11-12T10:15:00.000Z I Playback: text" becomes its time, tag ("Playback") and message. */
        internal fun parseAppLine(line: String): LogEntry {
            val space = line.indexOf(' ')
            val at = if (space > 0) {
                try {
                    Instant.parse(line.substring(0, space)).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            } else {
                null
            }
            if (at == null) return LogEntry(at = null, text = line)
            val rest = line.substring(space + 1)
            val colon = rest.indexOf(": ")
            if (rest.length < 3 || rest[1] != ' ' || colon < 0) return LogEntry(at = at, text = rest)
            val tag = rest.substring(2, colon)
            return LogEntry(at = at, text = rest.substring(colon + 2), subject = tag)
        }
    }
}
