/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.logs

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.StrictMode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import us.dikarya.podplayer.core.net.scrubUrls

/**
 * Writes a crash file (SPEC §13) for an uncaught exception, adds a line to the app log, then hands the
 * exception to the handler that was installed before, so Android still reports and ends the process.
 * Stack traces are redacted. Only the newest [KEEP] crash files are kept.
 */
class CrashHandler(
    private val log: AppLog,
    private val crashDir: () -> File,
    private val clock: () -> Long,
    private val appVersion: String,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // We are dying anyway; StrictMode must not get in the way of recording why.
        val policy = StrictMode.allowThreadDiskWrites()
        try {
            writeCrashFile(thread, error)
            log.logNow(AppLog.Level.Error, "Crash", "Uncaught exception on thread ${thread.name}", error)
        } catch (_: Throwable) {
            // Never let the crash handler crash.
        } finally {
            StrictMode.setThreadPolicy(policy)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrashFile(thread: Thread, error: Throwable) {
        val dir = crashDir()
        // mkdirs() is false when another thread made the folder first; only a folder that still isn't there stops us.
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return
        val now = clock()
        val text = buildString {
            appendLine("Podplayer $appVersion crashed at ${stamp.format(Instant.ofEpochMilli(now))}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(error.stackTraceToString())
        }
        // One crash at a time: two threads crashing together must not pick the same name or rotate each other's file.
        synchronized(lock) {
            val second = fileStamp.format(Instant.ofEpochMilli(now))
            // After the highest number this second already has, so a name freed by retention is never reused (it
            // would sort as the oldest and be deleted at once). createNewFile claims the name atomically.
            val used = dir.list().orEmpty().mapNotNull { name ->
                crashName.matchEntire(name)?.takeIf { it.groupValues[1] == second }?.let { it.groupValues[2].toIntOrNull() ?: 1 }
            }
            var n = (used.maxOrNull() ?: 0) + 1
            fun named(n: Int) = File(dir, if (n == 1) "crash-$second.txt" else "crash-$second-$n.txt")
            var file = named(n)
            while (!file.createNewFile()) file = named(++n)
            file.writeText(scrubUrls(text).take(MAX_BYTES))
            dir.listFiles { f -> f.name.startsWith("crash-") }
                ?.let { newestFirst(it.toList()) }
                ?.drop(KEEP)
                ?.forEach { it.delete() }
        }
    }

    companion object {
        const val KEEP = 10
        private const val MAX_BYTES = 256 * 1024
        private val stamp = DateTimeFormatter.ISO_INSTANT
        private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val lock = Any()
        private val crashName = Regex("^crash-(\\d{8}-\\d{6})(?:-(\\d{1,9}))?\\.txt$")

        /**
         * Crash files newest first: by their second, then by the number added for a second crash in the same
         * second (the first one has none). Names that aren't ours sort last, by name.
         */
        fun newestFirst(files: List<File>): List<File> = files.sortedWith(
            compareByDescending<File> { crashName.matchEntire(it.name)?.groupValues?.get(1) ?: "" }
                .thenByDescending { f -> crashName.matchEntire(f.name)?.let { it.groupValues[2].toIntOrNull() ?: 1 } ?: 0 }
                .thenByDescending { it.name },
        )

        /** Installs the handler in front of the current default one. */
        fun install(log: AppLog, crashDir: () -> File, appVersion: String, clock: () -> Long = System::currentTimeMillis) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(log, crashDir, clock, appVersion, previous))
        }
    }
}

/**
 * "ANR notes where available" (SPEC §13): Android 11+ remembers why earlier runs of the app ended (ANR, crash,
 * low memory, killed by the system). On start, the new ones go into the app log.
 */
object ExitReasons {
    /** Logs exits newer than [lastSeenAt]; returns the newest timestamp seen, to store for next time. */
    fun record(context: Context, log: AppLog, lastSeenAt: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lastSeenAt
        val am = context.getSystemService(ActivityManager::class.java) ?: return lastSeenAt
        val infos = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
        } catch (_: RuntimeException) {
            return lastSeenAt
        }
        var newest = lastSeenAt
        for (info in infos.filter { it.timestamp > lastSeenAt }.sortedBy { it.timestamp }) {
            // Redact before cutting: a cut just before a URL's '@' would hide its userinfo from the redaction.
            val description = info.description?.let { ": ${scrubUrls(it).take(500)}" } ?: ""
            log.i(
                "Exit",
                "An earlier run ended at ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(info.timestamp))}: " +
                    "${reasonName(info.reason)} (importance ${info.importance})$description",
            )
            newest = maxOf(newest, info.timestamp)
        }
        return newest
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $reason"
    }
}
