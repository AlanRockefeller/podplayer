/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.model.InvariantHandler
import us.dikarya.podplayer.core.model.StrictInvariantHandler

/** Decides which StrictMode thread violations are our bugs. */
internal object StrictModeFilter {
    private const val OUR_PACKAGE = "us.dikarya.podplayer."

    /**
     * Crash only when our code is on the stack. Vendor framework code also breaks the rules:
     * Samsung's `android.app.IdsController` reads SharedPreferences on the main thread during
     * every activity resume, with no app frame involved.
     */
    fun shouldCrash(trace: Array<StackTraceElement>): Boolean =
        trace.any { it.className.startsWith(OUR_PACKAGE) }
}

/** Debug builds: StrictMode on and invariants crash, so problems get noticed. */
internal object BuildTypeSetup {
    fun install() {
        // Disk or network work on the main thread is logged always, and crashes when it comes from our code.
        val main = Handler(Looper.getMainLooper())
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyListener({ main.post(it) }) { violation ->
                    if (StrictModeFilter.shouldCrash(violation.stackTrace)) {
                        throw IllegalStateException("StrictMode violation in Podplayer code", violation)
                    }
                }
                .build(),
        )
        // VM-wide checks also fire on framework and library internals, so they only log.
        // Untagged sockets are left out: OkHttp doesn't tag its sockets and that's not a defect.
        val vm = StrictMode.VmPolicy.Builder()
            .detectActivityLeaks()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectLeakedSqlLiteObjects()
            .detectFileUriExposure()
            .detectContentUriWithoutPermission()
            .detectCredentialProtectedWhileLocked()
            .detectImplicitDirectBoot()
            .detectNonSdkApiUsage()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vm.detectUnsafeIntentLaunch().detectIncorrectContextUse()
        }
        StrictMode.setVmPolicy(vm.penaltyLog().build())
    }

    /** Debug builds crash on a violated invariant (CLAUDE.md); the crash handler records it in the log first. */
    @Suppress("UNUSED_PARAMETER")
    fun invariantHandler(log: AppLog): InvariantHandler = StrictInvariantHandler
}
