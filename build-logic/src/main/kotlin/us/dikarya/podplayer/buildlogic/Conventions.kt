/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

internal fun VersionCatalog.versionOf(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int = versionOf(alias).toInt()

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

/** Bytecode level for every module. Java 17 is the safe floor for Android consumers. */
internal fun Project.jvmTargetVersion(): JavaVersion = JavaVersion.toVersion(libs.versionOf("jvmTarget"))

/** The one place that sets the JDK used to compile every module. */
internal fun Project.configureKotlinToolchain() {
    extensions.configure(KotlinBaseExtension::class.java) {
        jvmToolchain(libs.intVersion("jdk"))
    }
}

/** SDK levels, Java level and lint settings shared by the app and the Android libraries. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = libs.intVersion("compileSdk")
    android.defaultConfig.minSdk = libs.intVersion("minSdk")
    android.compileOptions.sourceCompatibility = jvmTargetVersion()
    android.compileOptions.targetCompatibility = jvmTargetVersion()
    android.lint.configurePodplayerLint()
    configureKotlinToolchain()
    dependencies.add("testImplementation", libs.library("junit"))
}

/** Lint is part of `./gradlew check` and any warning fails it. */
internal fun Lint.configurePodplayerLint() {
    abortOnError = true
    warningsAsErrors = true
    checkReleaseBuilds = true
    // These depend on what upstream released today, not on our code, so they would make CI
    // fail at random. Version bumps are a deliberate step (see README).
    disable += setOf(
        "GradleDependency",
        "NewerVersionAvailable",
        "AndroidGradlePluginVersion",
        "OldTargetApi",
    )
}
