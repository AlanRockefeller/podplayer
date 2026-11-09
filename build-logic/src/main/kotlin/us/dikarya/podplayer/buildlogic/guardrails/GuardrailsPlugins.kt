/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Property
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Settings for [GuardrailsModulePlugin]. Convention plugins set these; module build files should not. */
abstract class GuardrailsExtension {
    /** True for plain Kotlin (JVM) modules. */
    abstract val plainKotlin: Property<Boolean>
}

/** Header check over every source file of the repository. Apply to the root project only. */
class GuardrailsRootPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(LifecycleBasePlugin::class.java)
        val headers = project.tasks.register("checkLicenseHeaders", LicenseHeaderTask::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks that every source file starts with the MPL-2.0 Exhibit A notice."
            sources.from(
                project.fileTree(project.rootDir) {
                    LicenseHeader.EXTENSIONS.forEach { include("**/*.$it") }
                    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.idea/**", ".git/**")
                },
            )
            report.set(project.layout.buildDirectory.file("reports/guardrails/license-headers.txt"))
        }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(headers) }
    }
}

/** Forbidden-API and closed-dependency checks for one module, both attached to `check`. */
class GuardrailsModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(LifecycleBasePlugin::class.java)
        val extension = project.extensions.create("podplayerGuardrails", GuardrailsExtension::class.java)
        extension.plainKotlin.convention(false)

        val forbidden = project.tasks.register("checkForbiddenApis", ForbiddenApiTask::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks the module's sources for APIs that SPEC §14 and CLAUDE.md forbid."
            sources.from(
                project.fileTree(project.projectDir) {
                    ForbiddenApis.EXTENSIONS.forEach { include("src/**/*.$it") }
                },
            )
            projectPath.set(project.path)
            plainKotlin.set(extension.plainKotlin)
            report.set(project.layout.buildDirectory.file("reports/guardrails/forbidden-apis.txt"))
        }

        val closed = project.tasks.register("checkClosedDependencies", ClosedDependencyTask::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks every resolvable classpath for dependencies on the SPEC §2 \"Never\" list."
            // Evaluated when the task graph is stored, after every plugin has created its configurations.
            val configurations = project.configurations
            val buildscriptClasspath = project.buildscript.configurations
            graphs.putAll(
                project.provider {
                    val result = linkedMapOf<String, ResolvedComponentResult>()
                    configurations.filter(::isCheckedClasspath).forEach {
                        result[it.name] = it.incoming.resolutionResult.root
                    }
                    buildscriptClasspath.findByName("classpath")?.let {
                        result["buildscript classpath"] = it.incoming.resolutionResult.root
                    }
                    result
                },
            )
            report.set(project.layout.buildDirectory.file("reports/guardrails/closed-dependencies.txt"))
        }

        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(forbidden, closed) }
    }

    private fun isCheckedClasspath(configuration: Configuration): Boolean =
        configuration.isCanBeResolved &&
            (configuration.name.contains("classpath", ignoreCase = true) ||
                configuration.name.startsWith("ksp") ||
                configuration.name.endsWith("AnnotationProcessor") ||
                configuration.name == "annotationProcessor")
}
