/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Room database, repositories, migrations, backup and restore, settings, logs (M1-S4).
plugins {
    id("podplayer.android.library")
    id("podplayer.android.robolectric")
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "us.dikarya.podplayer.core.data"

    // The exported Room schemas double as test resources: the migration tests build every old
    // version of the database from its schema file (SchemaHistoryTest, MigrationTest).
    sourceSets.getByName("test").resources.directories.add("schemas")
}

// SPEC §7: schema export, committed with every version.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:feed"))
    implementation(project(":core:net"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
