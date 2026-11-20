/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// RSS/Atom/Podcasting 2.0 parsing, OPML, show-notes sanitizer, episode identity (M1-S3).
plugins {
    id("podplayer.jvm.library")
}

// Jazzer fuzz targets (M1-S11) live in their own source set on JUnit 5, so the JUnit 4 `test` task is untouched.
val fuzz: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[fuzz.implementationConfigurationName].extendsFrom(configurations.implementation.get())

dependencies {
    api(project(":core:model"))
    // UrlPolicy and redact(): one copy of the URL rules for the whole app.
    implementation(project(":core:net"))
    implementation(libs.jsoup)
    // The org.xmlpull.v1 interface the parsers are written against (SPEC §4). Android provides it at
    // runtime as part of the platform, so it is compile-only here and never ships in the app.
    compileOnly(libs.kxml2)

    testImplementation(project(":core:testing"))

    "fuzzImplementation"(project(":core:testing"))
    "fuzzImplementation"(libs.jazzer.junit)
    "fuzzImplementation"(platform(libs.junit.bom))
    "fuzzRuntimeOnly"(libs.junit.jupiter.engine)
    "fuzzRuntimeOnly"(libs.junit.platform.launcher)
}

// Seeds: the hostile corpus and the samples from :core:testing, plus findings kept in src/fuzz/findings,
// copied where Jazzer looks for them (`<TestClass>Inputs/` next to the class, on the classpath).
val testingResources = rootProject.file("core/testing/src/main/resources")
val seedsDir = layout.buildDirectory.dir("generated/fuzzSeeds")
val fuzzSeeds = tasks.register<Sync>("fuzzSeeds") {
    val pkg = "us/dikarya/podplayer/core/feed"
    into(seedsDir)
    from(testingResources.resolve("corpus/feeds")) { into("$pkg/FeedParserFuzzTestInputs") }
    from(testingResources.resolve("samples/feeds")) { into("$pkg/FeedParserFuzzTestInputs") }
    from(file("src/fuzz/findings/feed")) { into("$pkg/FeedParserFuzzTestInputs") }
    from(testingResources.resolve("corpus/opml")) { into("$pkg/OpmlParserFuzzTestInputs") }
    from(testingResources.resolve("samples/opml")) { into("$pkg/OpmlParserFuzzTestInputs") }
    from(file("src/fuzz/findings/opml")) { into("$pkg/OpmlParserFuzzTestInputs") }
}
fuzz.resources.srcDir(fuzzSeeds)

// Crash files and the generated corpus go under build/fuzz, never into the source tree.
val fuzzBaseDir = layout.buildDirectory.dir("fuzz")

fun Test.fuzzTestSetup() {
    testClassesDirs = fuzz.output.classesDirs
    classpath = fuzz.runtimeClasspath
    useJUnitPlatform()
    val base = fuzzBaseDir.get().asFile
    doFirst { base.mkdirs() }
    systemProperty("jazzer.internal.basedir", base.absolutePath)
    // Jazzer attaches its agent at run time.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    maxHeapSize = "2g"
}

// Regression mode: every target over its seeds and past findings. Part of `check`.
val fuzzRegression = tasks.register<Test>("fuzzRegression") {
    description = "Runs the Jazzer fuzz targets over their seeds and saved findings (no fuzzing)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    fuzzTestSetup()
}
tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(fuzzRegression) }

// Fuzzing mode: Jazzer fuzzes one target per JVM, so each target has its own task. Never up to date.
// Length: -PfuzzDuration=10m (default 60s). See README "Fuzzing".
val fuzzDuration = providers.gradleProperty("fuzzDuration").orElse("60s")
val fuzzTargets = mapOf("fuzzFeed" to "FeedParserFuzzTest", "fuzzOpml" to "OpmlParserFuzzTest")
fuzzTargets.forEach { (name, target) ->
    tasks.register<Test>(name) {
        description = "Fuzzes $target with Jazzer for -PfuzzDuration (default 60s)."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        fuzzTestSetup()
        filter.includeTestsMatching("us.dikarya.podplayer.core.feed.$target")
        environment("JAZZER_FUZZ", "1")
        systemProperty("jazzer.max_duration", fuzzDuration.get())
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}
tasks.register("fuzz") {
    description = "Fuzzes every target in turn."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(fuzzTargets.keys)
}
