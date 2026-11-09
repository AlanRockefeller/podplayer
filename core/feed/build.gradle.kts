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
    // UrlPolicy and redact(): one copy of the URL rules for the whole app.
    implementation(project(":core:net"))
    implementation(libs.jsoup)
    // The org.xmlpull.v1 interface the parsers are written against (SPEC §4). Android provides it at
    // runtime as part of the platform, so it is compile-only here and never ships in the app.
    compileOnly(libs.kxml2)

    testImplementation(project(":core:testing"))
}
