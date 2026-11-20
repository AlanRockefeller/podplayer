/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.logs

import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import us.dikarya.podplayer.core.net.scrubUrls

/**
 * The app log (SPEC §13): rolling files in app-private storage, `app.log` plus older `app.1.log`, `app.2.log`
 * and so on, [fileCount] files of at most [maxFileBytes] each (5 MiB in total by default).
 *
 * Every message and stack trace goes through `scrubUrls` (CLAUDE.md: feed URLs never appear unredacted), control
 * characters are replaced, and line breaks inside a message become indented continuation lines, so text from a
 * feed can't forge an entry. Writes happen on one background thread in order; logging never blocks the caller
 * on disk and never throws. Nothing leaves the phone unless the owner taps Export (M1-S9).
 */
class AppLog(
    private val dir: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFileBytes: Long = 1024L * 1024,
    private val fileCount: Int = 5,
    /** Also write to logcat (already redacted). */
    private val echo: Boolean = true,
) {
    enum class Level(val letter: Char, val priority: Int) {
        Debug('D', Log.DEBUG),
        Info('I', Log.INFO),
        Warn('W', Log.WARN),
        Error('E', Log.ERROR),
    }

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "podplayer-log").apply { isDaemon = true }
    }
    private val lock = Any()

    fun d(tag: String, message: String) = log(Level.Debug, tag, message, null)

    fun i(tag: String, message: String) = log(Level.Info, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.Warn, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.Error, tag, message, error)

    fun log(level: Level, tag: String, message: String, error: Throwable?) {
        val line = format(level, tag, message, error)
        if (echo) runCatching { Log.println(level.priority, "Podplayer", line) }
        try {
            writer.execute { append(line) }
        } catch (_: RejectedExecutionException) {
            // Closed: the process is going away.
        }
    }

    /** Writes on the calling thread, for the crash handler, which can't wait for the queue. */
    fun logNow(level: Level, tag: String, message: String, error: Throwable? = null) {
        append(format(level, tag, message, error))
    }

    /** Waits until everything logged so far is on disk. */
    fun flush() {
        try {
            writer.submit {}.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Closed or interrupted; nothing to wait for.
        }
    }

    fun close() {
        flush()
        writer.shutdown()
    }

    /** The log files, oldest first. */
    fun files(): List<File> {
        val folder = dir()
        return (fileCount - 1 downTo 0).map { fileAt(folder, it) }.filter { it.isFile }
    }

    private fun fileAt(folder: File, index: Int) = File(folder, if (index == 0) "app.log" else "app.$index.log")

    private fun append(line: String) {
        synchronized(lock) {
            try {
                val folder = dir()
                // mkdirs() is false when another thread made the folder first (the crash folder lives inside it):
                // only a folder that still isn't there drops the line.
                if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) return
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                val current = fileAt(folder, 0)
                if (current.length() + bytes.size > maxFileBytes && current.length() > 0) roll(folder)
                current.appendBytes(bytes)
            } catch (_: IOException) {
                // A full or broken disk must not take the app down with it.
            } catch (_: SecurityException) {
            }
        }
    }

    private fun roll(folder: File) {
        fileAt(folder, fileCount - 1).delete()
        for (i in fileCount - 2 downTo 0) {
            val from = fileAt(folder, i)
            if (from.exists()) from.renameTo(fileAt(folder, i + 1))
        }
    }

    private fun format(level: Level, tag: String, message: String, error: Throwable?): String {
        val text = buildString {
            append(message)
            if (error != null) append('\n').append(error.stackTraceToString().trimEnd())
        }
        // Redact before cutting, so a cut can't leave half a URL that redaction no longer recognises.
        val cleaned = clean(scrubUrls(text).take(MAX_LINE))
        val head = "${timestamp.format(Instant.ofEpochMilli(clock()))} ${level.letter} ${clean(tag).take(40)}: "
        return (head + cleaned).take(MAX_LINE)
    }

    companion object {
        const val MAX_LINE = 16 * 1024

        private val timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val lineBreak = Regex("\r\n|\r|\n")

        /** Line breaks become indented continuations; other control characters become '?'. */
        internal fun clean(text: String): String {
            val joined = text.replace(lineBreak, "\n    ")
            val out = StringBuilder(joined.length)
            for (c in joined) out.append(if (c < ' ' && c != '\n' || c == '\u007f') '?' else c)
            return out.toString()
        }
    }
}
