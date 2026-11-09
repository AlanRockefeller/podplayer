/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.os.Build
import android.os.StrictMode
import us.dikarya.podplayer.core.model.InvariantHandler
import us.dikarya.podplayer.core.model.StrictInvariantHandler

/** Debug builds: StrictMode on and invariants crash, so problems get noticed. */
internal object BuildTypeSetup {
    fun install() {
        // Disk or network work on the main thread is a bug in our code: crash.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeath()
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

    fun invariantHandler(): InvariantHandler = StrictInvariantHandler
}
