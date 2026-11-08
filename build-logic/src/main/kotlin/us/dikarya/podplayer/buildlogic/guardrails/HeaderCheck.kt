/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** The MPL-2.0 "Exhibit A" notice that every source file must start with (CLAUDE.md, Licensing). */
object LicenseHeader {
    val NOTICE: String = listOf(
        "/*",
        " * This Source Code Form is subject to the terms of the Mozilla Public",
        " * License, v. 2.0. If a copy of the MPL was not distributed with this",
        " * file, You can obtain one at https://mozilla.org/MPL/2.0/.",
        " */",
    ).joinToString("\n")

    /** File types checked. XML, TOML and YAML carry the notice too, but in their own comment syntax. */
    val EXTENSIONS: Set<String> = setOf("kt", "kts", "java", "aidl")

    /** True when [text] starts with exactly [NOTICE], followed by a line break or the end of the file. */
    fun hasNotice(text: String): Boolean {
        val normalized = text.replace("\r\n", "\n")
        return normalized == NOTICE || normalized.startsWith(NOTICE + "\n")
    }
}

/** Fails the build when a Kotlin, Kotlin script, Java or AIDL file does not start with [LicenseHeader.NOTICE]. */
@CacheableTask
abstract class LicenseHeaderTask : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val missing = mutableListOf<String>()
        sources.asFileTree.visit {
            if (!isDirectory && !LicenseHeader.hasNotice(file.readText())) missing += relativePath.pathString
        }
        missing.sort()
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(missing.joinToString("\n"))
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These files do not start with the MPL-2.0 Exhibit A notice:\n" +
                    missing.joinToString("\n") { "  $it" } +
                    "\n\nEvery source file must begin with exactly:\n${LicenseHeader.NOTICE}",
            )
        }
    }
}
