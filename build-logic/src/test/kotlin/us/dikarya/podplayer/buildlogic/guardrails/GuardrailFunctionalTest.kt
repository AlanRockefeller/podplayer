/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the real guardrail tasks in throwaway Gradle projects, proving that each one fails the
 * build on a bad sample and passes on clean code.
 */
class GuardrailFunctionalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val header = LicenseHeader.NOTICE + "\n\n"

    private fun fixture(dependencies: List<String> = emptyList()): File {
        val dir = tmp.newFolder("fixture")
        dir.resolve("settings.gradle.kts").writeText(header + "rootProject.name = \"fixture\"\n")
        val deps = dependencies.joinToString("\n") { "    implementation(\"$it\")" }
        dir.resolve("build.gradle.kts").writeText(
            header +
                """
                |plugins {
                |    `java-library`
                |    id("podplayer.guardrails.root")
                |    id("podplayer.guardrails.module")
                |}
                |repositories {
                |    maven { url = uri("repo") }
                |}
                |dependencies {
                |$deps
                |}
                |
                """.trimMargin(),
        )
        return dir
    }

    private fun File.addSource(relativePath: String, sample: String) {
        val file = resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(Samples.text(sample))
    }

    /** Writes a POM into the fixture's file-based Maven repository. */
    private fun File.publish(coordinate: String, dependsOn: List<String> = emptyList()) {
        val (group, artifact, version) = coordinate.split(":")
        val dir = resolve("repo/${group.replace('.', '/')}/$artifact/$version")
        dir.mkdirs()
        val deps = dependsOn.joinToString("") {
            val (g, a, v) = it.split(":")
            "<dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency>"
        }
        dir.resolve("$artifact-$version.pom").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0">
            |  <modelVersion>4.0.0</modelVersion>
            |  <groupId>$group</groupId>
            |  <artifactId>$artifact</artifactId>
            |  <version>$version</version>
            |  <packaging>pom</packaging>
            |  <dependencies>$deps</dependencies>
            |</project>
            """.trimMargin(),
        )
    }

    private fun runner(dir: File, vararg tasks: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*tasks, "--configuration-cache", "--stacktrace")

    private fun BuildResult.assertOutcome(task: String, outcome: TaskOutcome) =
        assertEquals(output, outcome, task(task)?.outcome)

    @Test
    fun headerCheckFailsOnAFileWithoutTheNotice() {
        val dir = fixture()
        dir.addSource("src/main/kotlin/sample/MissingHeader.kt", "MissingHeader.kt")
        val result = runner(dir, "checkLicenseHeaders").buildAndFail()
        result.assertOutcome(":checkLicenseHeaders", TaskOutcome.FAILED)
        assertTrue(result.output, "src/main/kotlin/sample/MissingHeader.kt" in result.output)
    }

    @Test
    fun headerCheckPassesOnCleanFiles() {
        val dir = fixture()
        dir.addSource("src/main/kotlin/sample/Good.kt", "Good.kt")
        runner(dir, "checkLicenseHeaders").build().assertOutcome(":checkLicenseHeaders", TaskOutcome.SUCCESS)
    }

    @Test
    fun headerCheckIgnoresBuildOutputs() {
        val dir = fixture()
        dir.addSource("build/generated/Generated.kt", "MissingHeader.kt")
        runner(dir, "checkLicenseHeaders").build().assertOutcome(":checkLicenseHeaders", TaskOutcome.SUCCESS)
    }

    @Test
    fun forbiddenApiCheckFailsOnWebView() {
        val dir = fixture()
        dir.addSource("src/main/kotlin/sample/WebViewImport.kt", "WebViewImport.kt")
        val result = runner(dir, "checkForbiddenApis").buildAndFail()
        result.assertOutcome(":checkForbiddenApis", TaskOutcome.FAILED)
        assertTrue(result.output, "src/main/kotlin/sample/WebViewImport.kt:9" in result.output)
    }

    @Test
    fun forbiddenApiCheckPassesOnCleanCode() {
        val dir = fixture()
        dir.addSource("src/main/kotlin/sample/Good.kt", "Good.kt")
        runner(dir, "checkForbiddenApis").build().assertOutcome(":checkForbiddenApis", TaskOutcome.SUCCESS)
    }

    @Test
    fun checkRunsEveryGuardrail() {
        val dir = fixture()
        dir.addSource("src/main/kotlin/sample/Good.kt", "Good.kt")
        val result = runner(dir, "check").build()
        result.assertOutcome(":checkLicenseHeaders", TaskOutcome.SUCCESS)
        result.assertOutcome(":checkForbiddenApis", TaskOutcome.SUCCESS)
        result.assertOutcome(":checkClosedDependencies", TaskOutcome.SUCCESS)
    }

    @Test
    fun closedDependencyCheckFailsOnADirectBannedDependency() {
        val dir = fixture(listOf("com.google.firebase:firebase-fake:1.0"))
        dir.publish("com.google.firebase:firebase-fake:1.0")
        val result = runner(dir, "checkClosedDependencies").buildAndFail()
        result.assertOutcome(":checkClosedDependencies", TaskOutcome.FAILED)
        assertTrue(result.output, "com.google.firebase:firebase-fake:1.0" in result.output)
    }

    @Test
    fun closedDependencyCheckFailsOnATransitiveBannedDependency() {
        val dir = fixture(listOf("org.example:innocent:1.0"))
        dir.publish("org.example:innocent:1.0", dependsOn = listOf("com.google.android.gms:play-services-fake:1.0"))
        dir.publish("com.google.android.gms:play-services-fake:1.0")
        val result = runner(dir, "checkClosedDependencies").buildAndFail()
        result.assertOutcome(":checkClosedDependencies", TaskOutcome.FAILED)
        assertTrue(result.output, "com.google.android.gms:play-services-fake:1.0" in result.output)
    }

    @Test
    fun closedDependencyCheckFailsEvenWhenTheBannedDependencyCannotBeResolved() {
        val dir = fixture(listOf("com.google.firebase:not-in-any-repo:1.0"))
        val result = runner(dir, "checkClosedDependencies").buildAndFail()
        assertTrue(result.output, "com.google.firebase:not-in-any-repo" in result.output)
    }

    @Test
    fun closedDependencyCheckPassesOnOpenDependencies() {
        val dir = fixture(listOf("org.example:clean:1.0"))
        dir.publish("org.example:clean:1.0", dependsOn = listOf("org.example:also-clean:1.0"))
        dir.publish("org.example:also-clean:1.0")
        runner(dir, "checkClosedDependencies").build()
            .assertOutcome(":checkClosedDependencies", TaskOutcome.SUCCESS)
    }
}
