/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Isolated image decode service, client, image cache and Coil setup (M1-S6).
// The only module where the forbidden-API check allows platform image decoders.
plugins {
    id("podplayer.android.library")
}

android {
    namespace = "us.dikarya.podplayer.core.imagesandbox"
}

dependencies {
    implementation(project(":core:net"))
}
