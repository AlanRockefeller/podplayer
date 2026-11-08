/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** A banned group (and its sub-groups), optionally narrowed to artifacts whose name starts with [artifactPrefix]. */
data class BannedCoordinate(val group: String, val artifactPrefix: String? = null, val reason: String) {
    fun matches(group: String, artifact: String): Boolean {
        val groupMatches = group == this.group || group.startsWith(this.group + ".")
        return groupMatches && (artifactPrefix == null || artifact.startsWith(artifactPrefix))
    }
}

/** The SPEC §2 "Never" list as coordinates. */
object ClosedDependencies {
    private const val CLOSED = "closed-source Google services"
    private const val CRASH = "crash reporting or analytics SDK"
    private const val ADS = "ad SDK"
    private const val WEBVIEW = "WebView-based library"
    private const val NATIVE = "native image or media library"

    val banned: List<BannedCoordinate> = listOf(
        BannedCoordinate("com.google.android.gms", reason = CLOSED),
        BannedCoordinate("com.google.gms", reason = CLOSED),
        BannedCoordinate("com.google.firebase", reason = CLOSED),
        BannedCoordinate("com.google.android.play", reason = CLOSED),
        BannedCoordinate("com.google.android.ads", reason = ADS),
        BannedCoordinate("com.crashlytics", reason = CRASH),
        BannedCoordinate("io.fabric", reason = CRASH),
        BannedCoordinate("io.sentry", reason = CRASH),
        BannedCoordinate("com.bugsnag", reason = CRASH),
        BannedCoordinate("ch.acra", reason = CRASH),
        BannedCoordinate("com.microsoft.appcenter", reason = CRASH),
        BannedCoordinate("com.instabug", reason = CRASH),
        BannedCoordinate("com.newrelic", reason = CRASH),
        BannedCoordinate("com.datadoghq", reason = CRASH),
        BannedCoordinate("com.amplitude", reason = CRASH),
        BannedCoordinate("com.mixpanel", reason = CRASH),
        BannedCoordinate("com.segment", reason = CRASH),
        BannedCoordinate("com.flurry", reason = CRASH),
        BannedCoordinate("com.appsflyer", reason = CRASH),
        BannedCoordinate("com.adjust", reason = CRASH),
        BannedCoordinate("io.branch", reason = CRASH),
        BannedCoordinate("com.facebook", reason = "Facebook SDKs (analytics, ads) and Fresco (native image decoding)"),
        BannedCoordinate("com.applovin", reason = ADS),
        BannedCoordinate("com.unity3d.ads", reason = ADS),
        BannedCoordinate("com.ironsource", reason = ADS),
        BannedCoordinate("androidx.webkit", reason = WEBVIEW),
        BannedCoordinate("com.google.accompanist", artifactPrefix = "accompanist-webview", reason = WEBVIEW),
        BannedCoordinate("pl.droidsonroids.gif", reason = NATIVE),
        BannedCoordinate("com.arthenica", reason = "FFmpeg"),
        BannedCoordinate("org.bytedeco", reason = "FFmpeg and other native libraries"),
        BannedCoordinate("androidx.media3", artifactPrefix = "media3-decoder-ffmpeg", reason = "FFmpeg"),
        BannedCoordinate("androidx.media3", artifactPrefix = "media3-exoplayer-ima", reason = ADS),
        BannedCoordinate("io.coil-kt.coil3", artifactPrefix = "coil-network", reason = "Coil network fetching"),
    )

    fun findBanned(group: String, artifact: String): BannedCoordinate? =
        banned.firstOrNull { it.matches(group, artifact) }
}

/**
 * Fails the build when any resolvable classpath of the module, or its build script classpath,
 * contains a banned coordinate, directly or transitively. Dependencies that fail to resolve are
 * checked by their requested coordinate, so a banned one can't hide behind a resolution error.
 */
@DisableCachingByDefault(because = "Inputs are dependency graphs; the check is cheap")
abstract class ClosedDependencyTask : DefaultTask() {
    /** Configuration name to the root of its resolved dependency graph. */
    @get:Input
    abstract val graphs: MapProperty<String, ResolvedComponentResult>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val findings = sortedSetOf<String>()
        for ((configuration, root) in graphs.get()) {
            val seen = mutableSetOf<Any>()
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (!seen.add(component.id)) continue
                (component.id as? ModuleComponentIdentifier)?.let { id ->
                    ClosedDependencies.findBanned(id.group, id.module)?.let {
                        findings += "$configuration: ${id.group}:${id.module}:${id.version} (${it.reason})"
                    }
                }
                for (dependency in component.dependencies) {
                    when (dependency) {
                        is ResolvedDependencyResult -> pending.addLast(dependency.selected)
                        is UnresolvedDependencyResult -> (dependency.requested as? ModuleComponentSelector)?.let { s ->
                            ClosedDependencies.findBanned(s.group, s.module)?.let {
                                findings += "$configuration: ${s.group}:${s.module}:${s.version} (unresolved; ${it.reason})"
                            }
                        }
                    }
                }
            }
        }
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            buildString {
                appendLine("Checked configurations:")
                graphs.get().keys.sorted().forEach { appendLine("  $it") }
                appendLine(if (findings.isEmpty()) "No banned dependencies." else "Banned dependencies:")
                findings.forEach { appendLine("  $it") }
            },
        )
        if (findings.isNotEmpty()) {
            throw GradleException(
                "Banned dependencies (SPEC §2 \"Never\" list) found:\n" + findings.joinToString("\n") { "  $it" },
            )
        }
    }
}
