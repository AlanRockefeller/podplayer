/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Fixtures, hostile corpus and fakes shared by the tests of other modules.
// Only test source sets may depend on this module.
plugins {
    id("podplayer.jvm.library")
}

dependencies {
    api(project(":core:model"))
    // The JVM XmlPullParser implementation used in tests (SPEC §2, §4).
    api(libs.kxml2)
}
