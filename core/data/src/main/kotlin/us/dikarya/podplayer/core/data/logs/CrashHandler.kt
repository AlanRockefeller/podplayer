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
        if (!dir.isDirectory && !dir.mkdirs()) return
        val now = clock()
        val text = buildString {
            appendLine("Podplayer $appVersion crashed at ${stamp.format(Instant.ofEpochMilli(now))}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(error.stackTraceToString())
        }
        var file = File(dir, "crash-${fileStamp.format(Instant.ofEpochMilli(now))}.txt")
        var n = 2
        while (file.exists()) file = File(dir, "crash-${fileStamp.format(Instant.ofEpochMilli(now))}-${n++}.txt")
        file.writeText(scrubUrls(text).take(MAX_BYTES))
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    companion object {
        const val KEEP = 10
        private const val MAX_BYTES = 256 * 1024
        private val stamp = DateTimeFormatter.ISO_INSTANT
        private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

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
            val description = info.description?.let { ": ${it.take(500)}" } ?: ""
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
