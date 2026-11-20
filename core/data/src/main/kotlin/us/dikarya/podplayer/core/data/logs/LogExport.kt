/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.logs

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import us.dikarya.podplayer.core.data.repo.LogRepository
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.net.scrubUrls

/** One crash file, already redacted. */
class CrashReport(val name: String, val text: String)

/**
 * Settings › Logs (SPEC §13): reads the app log and the crash files for the viewer, and writes the export the
 * owner shares. The export holds the app log, the crash files and the refresh, download and position logs.
 * Every line goes through `scrubUrls` again on the way out, so even text written before redaction existed
 * leaves the phone without queries, fragments or userinfo (CLAUDE.md: feed URLs are secrets).
 *
 * Nothing here sends anything anywhere: [writeTo] writes to the stream it is given, and the app calls it only
 * when the owner taps Export.
 */
class LogExport(
    private val log: AppLog,
    private val crashDir: () -> File,
    private val repositories: suspend () -> Repositories?,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** The newest [maxLines] lines of the app log, oldest first. */
    suspend fun appLines(maxLines: Int = VIEWER_LINES): List<String> = withContext(Dispatchers.IO) {
        log.flush()
        val lines = ArrayDeque<String>()
        for (file in log.files()) {
            readText(file, MAX_FILE_BYTES).lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                lines.addLast(scrubUrls(line))
                if (lines.size > maxLines) lines.removeFirst()
            }
        }
        lines.toList()
    }

    /** Crash files, newest first, redacted. */
    suspend fun crashes(): List<CrashReport> = withContext(Dispatchers.IO) {
        val files = crashDir().listFiles { f -> f.isFile && f.name.startsWith("crash-") }.orEmpty()
        files.sortedByDescending { it.name }.map { CrashReport(it.name, scrubUrls(readText(it, MAX_FILE_BYTES))) }
    }

    /** Writes the whole export as UTF-8 text. Returns the number of bytes written. */
    suspend fun writeTo(out: OutputStream): Long = withContext(Dispatchers.IO) {
        val writer = CappedWriter(out, maxBytes)
        writer.line("Podplayer $appVersion log export, ${stamp(clock())}")
        writer.line("Addresses have their query, fragment and user name removed.")

        writer.heading(APP_HEADING)
        appLines(Int.MAX_VALUE).forEach(writer::line)

        writer.heading(CRASH_HEADING)
        crashes().forEach { crash ->
            writer.line("--- ${crash.name}")
            crash.text.lineSequence().forEach(writer::line)
        }

        val repos = repositories()
        writer.heading(REFRESH_HEADING)
        if (repos == null) {
            writer.line(LIBRARY_CLOSED)
        } else {
            repos.logs.refreshLog(LogRepository.KEEP_ROWS).first().forEach { row ->
                writer.line("${stamp(row.at)} feed ${row.feedId}: ${row.outcome} (${row.newEpisodes} new, ${row.updatedEpisodes} updated) ${row.message.orEmpty()}")
            }
        }
        writer.heading(DOWNLOAD_HEADING)
        if (repos == null) {
            writer.line(LIBRARY_CLOSED)
        } else {
            repos.logs.downloadLog(LogRepository.KEEP_ROWS).first().forEach { row ->
                writer.line("${stamp(row.at)} episode ${row.episodeId}: ${row.event} ${row.message.orEmpty()}")
            }
        }
        writer.heading(POSITION_HEADING)
        if (repos == null) {
            writer.line(LIBRARY_CLOSED)
        } else {
            repos.logs.positionLog(LogRepository.KEEP_POSITION_ROWS).first().forEach { change ->
                val source = if (change.streaming) "stream" else "file"
                writer.line("${stamp(change.at)} episode ${change.episodeId}: ${clockText(change.fromMs)} -> ${clockText(change.toMs)} ${change.cause} ($source)")
            }
        }
        writer.finish()
    }

    /** Stops at [limit] bytes with a note, so a huge log can't make a huge file. */
    private class CappedWriter(private val out: OutputStream, private val limit: Long) {
        private var written = 0L
        private var full = false

        fun heading(text: String) {
            line("")
            line(text)
        }

        fun line(text: String) {
            if (full) return
            val bytes = (AppLog.clean(scrubUrls(text)) + "\n").toByteArray(Charsets.UTF_8)
            if (written + bytes.size > limit) {
                full = true
                val note = "\n$TRUNCATED\n".toByteArray(Charsets.UTF_8)
                out.write(note)
                written += note.size
                return
            }
            out.write(bytes)
            written += bytes.size
        }

        fun finish(): Long {
            out.flush()
            return written
        }
    }

    companion object {
        const val MAX_BYTES = 12L * 1024 * 1024
        const val VIEWER_LINES = 1_000
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024

        const val APP_HEADING = "=== App log ==="
        const val CRASH_HEADING = "=== Crash files ==="
        const val REFRESH_HEADING = "=== Refresh log ==="
        const val DOWNLOAD_HEADING = "=== Download log ==="
        const val POSITION_HEADING = "=== Position log ==="
        const val TRUNCATED = "[The export stops here: it reached its size limit.]"
        private const val LIBRARY_CLOSED = "(The library couldn't be opened.)"

        private val timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        fun stamp(at: Long): String = timestamp.format(Instant.ofEpochMilli(at))

        /** 70,000 ms is "1:10"; an hour or more is "1:02:03". */
        fun clockText(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }

        private fun readText(file: File, limit: Int): String = try {
            file.inputStream().use { input ->
                // InputStream.readNBytes is API 33+; minSdk is 29.
                val buffer = ByteArray(minOf(limit.toLong(), file.length().coerceAtLeast(0)).toInt())
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                String(buffer, 0, filled, Charsets.UTF_8)
            }
        } catch (_: IOException) {
            ""
        } catch (_: SecurityException) {
            ""
        }
    }
}
