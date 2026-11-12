/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import com.android.build.api.variant.HostTestBuilder
import java.util.Properties

plugins {
    id("podplayer.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("podplayer.android.robolectric")
}

// Signing key: it lives outside the repository (README, "Release signing"). Without the file,
// for example on CI, release builds are unsigned and debug builds use the standard debug key.
val signingFile: Provider<String> = providers.gradleProperty("podplayer.signing.file")
    .orElse(providers.systemProperty("user.home").map { "$it/keystores/podplayer/signing.properties" })
val signing: Properties? = providers
    .fileContents(objects.fileProperty().fileProvider(signingFile.map(::File)))
    .asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

android {
    namespace = "us.dikarya.podplayer"

    defaultConfig {
        applicationId = "us.dikarya.podplayer"
        versionCode = 1
        versionName = "0.1.0"
    }

    if (signing != null) {
        signingConfigs {
            create("podplayer") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
        buildTypes {
            // Debug builds use the same key, so the debug build used daily during M1 can be
            // replaced by a release build without uninstalling (which would delete its data).
            getByName("debug") { signingConfig = signingConfigs.getByName("podplayer") }
            getByName("release") { signingConfig = signingConfigs.getByName("podplayer") }
        }
    }

    buildFeatures {
        compose = true
        // VERSION_NAME goes into the user agent (SPEC §3).
        buildConfig = true
    }
}

// Unit tests run for the release variant too, so `src/testRelease` can prove what release builds leave out
// (the Developer screen, SPEC §12). AGP only creates debug unit tests by default.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:feed"))
    implementation(project(":core:net"))
    implementation(project(":core:data"))
    implementation(project(":core:imagesandbox"))
    implementation(project(":core:jobs"))
    implementation(project(":playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The browsing screens' view models (M1-S8).
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Shows artwork from sandbox-produced cache files only, through :core:imagesandbox's guarded loader.
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlinx.coroutines.test)
    // Compose UI tests under Robolectric. Not ui-test-manifest: it would add an exported empty activity to the
    // debug build the owner uses daily; the tests register their host activity with Robolectric instead.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Raises the Espresso that ui-test-junit4 brings (3.5.0 can't idle on the SDK 37 Robolectric jar).
    testImplementation(libs.androidx.test.espresso.core)
}
