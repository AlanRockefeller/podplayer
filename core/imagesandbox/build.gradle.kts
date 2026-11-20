/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Isolated image decode service, client, image cache, Coil setup and the artwork provider (M1-S6).
// Platform image decoders may appear only in this module's `sandbox` package, whose code runs in the
// isolated process (forbidden-API rule `image-decoding`).
plugins {
    id("podplayer.android.library")
    id("podplayer.android.robolectric")
}

android {
    namespace = "us.dikarya.podplayer.core.imagesandbox"

    buildFeatures {
        // The one binder interface between the app and the sandbox process (src/main/aidl).
        aidl = true
    }
}

dependencies {
    api(project(":core:net"))
    // Coil 3 core only: no network artifact, and its service loader is switched off (coil/SandboxImageLoader.kt).
    api(libs.coil.core)
    implementation(libs.kotlinx.coroutines.android)
    constraints {
        // Coil 3.6.3 asks for lifecycle 2.9.4, whose bundled lint checks are too old for this lint. The app
        // ships the pinned version (2.11.0) anyway; this module and its consumers resolve the same.
        api(libs.androidx.lifecycle.runtime)
    }

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
