/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
    `kotlin-dsl`
}

group = "us.dikarya.podplayer.buildlogic"

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

tasks.test {
    // The functional tests start nested Gradle builds; give them room and show failures in full.
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

gradlePlugin {
    plugins {
        register("root") {
            id = "podplayer.root"
            implementationClass = "us.dikarya.podplayer.buildlogic.RootConventionPlugin"
        }
        register("androidApplication") {
            id = "podplayer.android.application"
            implementationClass = "us.dikarya.podplayer.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "podplayer.android.library"
            implementationClass = "us.dikarya.podplayer.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "podplayer.jvm.library"
            implementationClass = "us.dikarya.podplayer.buildlogic.JvmLibraryConventionPlugin"
        }
        register("guardrailsRoot") {
            id = "podplayer.guardrails.root"
            implementationClass = "us.dikarya.podplayer.buildlogic.guardrails.GuardrailsRootPlugin"
        }
        register("guardrailsModule") {
            id = "podplayer.guardrails.module"
            implementationClass = "us.dikarya.podplayer.buildlogic.guardrails.GuardrailsModulePlugin"
        }
    }
}
