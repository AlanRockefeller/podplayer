/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// MediaLibraryService, ExoPlayer setup, queue projection, sleep timer, resumption, Android Auto (M1-S7, S10).
// M1-S6 adds the artwork bitmap loader, so Media3 only ever shows sandbox-produced cache files.
plugins {
    id("podplayer.android.library")
    id("podplayer.android.robolectric")
}

android {
    namespace = "us.dikarya.podplayer.playback"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:net"))
    implementation(project(":core:imagesandbox"))
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
