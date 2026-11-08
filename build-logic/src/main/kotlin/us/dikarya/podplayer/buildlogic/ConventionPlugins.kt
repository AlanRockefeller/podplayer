/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import us.dikarya.podplayer.buildlogic.guardrails.GuardrailsExtension
import us.dikarya.podplayer.buildlogic.guardrails.GuardrailsModulePlugin
import us.dikarya.podplayer.buildlogic.guardrails.GuardrailsRootPlugin

/** Root project: header check, root-level guardrails, and build-logic's own tests under `check`. */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(GuardrailsRootPlugin::class.java)
        project.pluginManager.apply(GuardrailsModulePlugin::class.java)
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
            dependsOn(project.gradle.includedBuild("build-logic").task(":check"))
        }
    }
}

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.application")
        project.pluginManager.apply(GuardrailsModulePlugin::class.java)
        project.extensions.configure(ApplicationExtension::class.java) {
            project.configureAndroidCommon(this)
            defaultConfig.targetSdk = project.libs.intVersion("targetSdk")
            // Lint the plain Kotlin modules too, so NewApi catches JDK APIs missing on minSdk.
            lint.checkDependencies = true
        }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")
        project.pluginManager.apply(GuardrailsModulePlugin::class.java)
        project.extensions.configure(LibraryExtension::class.java) {
            project.configureAndroidCommon(this)
        }
    }
}

/** Plain Kotlin module: no Android, Java 17 API surface, linted for minSdk through `:app`. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("com.android.lint")
        project.pluginManager.apply(GuardrailsModulePlugin::class.java)
        project.extensions.configure(GuardrailsExtension::class.java) { plainKotlin.set(true) }

        project.configureKotlinToolchain()
        val target = project.libs.versionOf("jvmTarget")
        project.tasks.withType(KotlinJvmCompile::class.java).configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(target))
                // Compile against the JDK 17 class library, not the JDK 21 one we run on.
                freeCompilerArgs.add("-Xjdk-release=$target")
            }
        }
        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.release.set(target.toInt())
        }
        project.extensions.configure(Lint::class.java) { configurePodplayerLint() }
        project.dependencies.add("testImplementation", project.libs.library("junit"))
    }
}
