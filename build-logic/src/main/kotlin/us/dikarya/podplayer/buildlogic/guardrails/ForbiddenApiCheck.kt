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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Where a scanned file lives. */
data class ModuleContext(
    /** Gradle path, for example `:core:imagesandbox`. */
    val projectPath: String,
    /** True for plain Kotlin (JVM) modules, which must stay free of `android.*`. */
    val plainKotlin: Boolean,
)

data class Violation(
    /** Path relative to the module directory, with `/` separators. */
    val file: String,
    val line: Int,
    val ruleId: String,
    val match: String,
    val reason: String,
)

class ForbiddenApiRule(
    val id: String,
    val reason: String,
    val pattern: Regex,
    val extensions: Set<String>,
    /** Whether the rule applies to this file; receives the module and the module-relative path. */
    val appliesTo: (ModuleContext, String) -> Boolean = { _, _ -> true },
)

/**
 * Source-level ban list from SPEC §14 and the CLAUDE.md security rules.
 *
 * The scan is a plain token search that includes comments and strings: strict on purpose,
 * so a forbidden name cannot slip in through a fully qualified reference, an import alias,
 * reflection by name or an XML tag.
 */
object ForbiddenApis {
    const val IMAGE_SANDBOX = ":core:imagesandbox"
    const val APP = ":app"
    const val NET = ":core:net"

    val EXTENSIONS: Set<String> = setOf("kt", "java", "xml", "aidl")

    private val code = setOf("kt", "java", "aidl")
    private val codeAndXml = setOf("kt", "java", "aidl", "xml")

    val rules: List<ForbiddenApiRule> = listOf(
        ForbiddenApiRule(
            id = "webview",
            reason = "Never use WebView. Show notes go through the sanitizer and render as native text.",
            pattern = Regex("""\bWebView\w*|\bandroid\.webkit\b"""),
            extensions = codeAndXml,
        ),
        ForbiddenApiRule(
            id = "media-extractor",
            reason = "Never use android.media.MediaExtractor; probe with Media3's JVM extractors.",
            pattern = Regex("""\bMediaExtractor\b"""),
            extensions = code,
        ),
        ForbiddenApiRule(
            id = "media-metadata-retriever",
            reason = "Never use MediaMetadataRetriever; use Media3's MetadataRetriever.",
            pattern = Regex("""\bMediaMetadataRetriever\b"""),
            extensions = code,
        ),
        ForbiddenApiRule(
            id = "image-decoding",
            reason = "Untrusted image bytes are decoded only inside $IMAGE_SANDBOX.",
            pattern = Regex(
                """\b(BitmapFactory|ImageDecoder|BitmapRegionDecoder)\b|\bDrawable\.createFrom(Stream|Path)\b""",
            ),
            extensions = code,
            appliesTo = { module, _ -> module.projectPath != IMAGE_SANDBOX },
        ),
        ForbiddenApiRule(
            id = "other-http-client",
            reason = "All network traffic goes through the gated OkHttp client in :core:net.",
            pattern = Regex("""\bHttpsURLConnection\b|\bHttpURLConnection\b|\.openConnection\(|\.openStream\("""),
            extensions = code,
        ),
        ForbiddenApiRule(
            id = "okhttp-client-outside-net",
            reason = "Only $NET builds OkHttp clients; other modules get a gated Call.Factory from NetGate.",
            pattern = Regex("""\bOkHttpClient\b"""),
            extensions = code,
            appliesTo = { module, _ -> module.projectPath != NET },
        ),
        ForbiddenApiRule(
            id = "coil-network",
            reason = "Coil must not fetch from the network; it only loads sandbox-produced cache files.",
            pattern = Regex("""\bcoil3\.network\b|\bcoil-network\b"""),
            extensions = codeAndXml,
        ),
        ForbiddenApiRule(
            id = "android-in-plain-module",
            reason = "Plain Kotlin modules must not import android.*, so they can move to Kotlin Multiplatform.",
            pattern = Regex("""^\s*import\s+android\.""", RegexOption.MULTILINE),
            extensions = code,
            appliesTo = { module, _ -> module.plainKotlin },
        ),
        ForbiddenApiRule(
            id = "hard-coded-color",
            reason = "Screens use the semantic color tokens; hex colors live only in the ui/theme package.",
            pattern = Regex(
                """\bColor\(\s*0x[0-9A-Fa-f]|\bColor\.(Black|White|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b""",
            ),
            extensions = setOf("kt"),
            appliesTo = { module, path -> module.projectPath == APP && "/ui/theme/" !in path },
        ),
    )

    fun scan(module: ModuleContext, relativePath: String, text: String): List<Violation> {
        val extension = relativePath.substringAfterLast('.', "")
        return rules
            .filter { extension in it.extensions && it.appliesTo(module, relativePath) }
            .flatMap { rule ->
                rule.pattern.findAll(text).map { match ->
                    Violation(
                        file = relativePath,
                        line = lineOf(text, match.range.first),
                        ruleId = rule.id,
                        match = match.value.trim(),
                        reason = rule.reason,
                    )
                }
            }
            .sortedWith(compareBy({ it.line }, { it.ruleId }))
    }

    private fun lineOf(text: String, index: Int): Int {
        // A MULTILINE match can start on the preceding line break; skip leading whitespace.
        var start = index
        while (start < text.length && text[start].isWhitespace()) start++
        return 1 + (0 until start.coerceAtMost(text.length)).count { text[it] == '\n' }
    }
}

/** Fails the build when a module's sources use an API that SPEC §14 or CLAUDE.md forbids. */
@CacheableTask
abstract class ForbiddenApiTask : DefaultTask() {
    /** Files under the module's `src/` directory, as a tree rooted at the module directory. */
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val plainKotlin: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val module = ModuleContext(projectPath.get(), plainKotlin.get())
        val violations = mutableListOf<Violation>()
        sources.asFileTree.visit {
            if (!isDirectory) violations += ForbiddenApis.scan(module, relativePath.pathString, file.readText())
        }
        violations.sortWith(compareBy({ it.file }, { it.line }))
        val lines = violations.map { "${it.file}:${it.line}: [${it.ruleId}] '${it.match}': ${it.reason}" }
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(lines.joinToString("\n"))
        if (lines.isNotEmpty()) {
            throw GradleException(
                "Forbidden APIs in ${module.projectPath}:\n" + lines.joinToString("\n") { "  $it" },
            )
        }
    }
}
