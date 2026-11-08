/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// MediaLibraryService, ExoPlayer setup, queue projection, sleep timer, resumption, Android Auto (M1-S7, S10).
plugins {
    id("podplayer.android.library")
}

android {
    namespace = "us.dikarya.podplayer.playback"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:net"))
}
