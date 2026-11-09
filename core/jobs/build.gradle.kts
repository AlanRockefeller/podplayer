/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// WorkManager workers: the daily backup (M1-S4), refresh and downloads (M1-S5).
plugins {
    id("podplayer.android.library")
    id("podplayer.android.robolectric")
}

android {
    namespace = "us.dikarya.podplayer.core.jobs"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:net"))
    implementation(project(":core:feed"))
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Only for its extractors (media3-extractor, part of its API): downloads are probed with them (SPEC §9).
    implementation(libs.media3.exoplayer)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}
