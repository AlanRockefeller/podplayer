/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// RSS/Atom/Podcasting 2.0 parsing, OPML, show-notes sanitizer, episode identity (M1-S3).
plugins {
    id("podplayer.jvm.library")
}

dependencies {
    api(project(":core:model"))
    testImplementation(project(":core:testing"))
}
