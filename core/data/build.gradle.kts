/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Room database, repositories, migrations, backup and restore, logs (M1-S4).
plugins {
    id("podplayer.android.library")
}

android {
    namespace = "us.dikarya.podplayer.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:feed"))
}
