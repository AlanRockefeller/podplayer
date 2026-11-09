/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// WorkManager refresh and download workers (M1-S5).
plugins {
    id("podplayer.android.library")
}

android {
    namespace = "us.dikarya.podplayer.core.jobs"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:net"))
    implementation(project(":core:feed"))
}
