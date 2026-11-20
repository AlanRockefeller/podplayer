/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md: the Developer screen lives in the `debug` source set only, and nothing in release builds may reach
 * it. Not just the screen's known names ([DeveloperScreenAbsentTest]): every class declared in a file under
 * `app/src/debug` and every resource there is checked against the release variant's own classes (the list AGP
 * packages from this project, written by `listReleaseClasses`) and its `R` class. A new debug-only file is
 * covered without touching this test.
 */
class DebugSourceSetAbsentTest {
    private fun dir(property: String) = File(checkNotNull(System.getProperty(property)) { "$property not set by the build" })

    private fun classList(property: String): Set<String> = dir(property).readLines().filter { it.isNotBlank() }.toSet()

    private fun sources(root: File): List<File> =
        root.resolve("kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The JVM names a Kotlin file can produce: its top-level classes and objects, and its file facade. */
    private fun declaredNames(file: File): Set<String> {
        val text = file.readText()
        val pkg = PACKAGE.find(text)?.groupValues?.get(1)?.let { "$it." }.orEmpty()
        val classes = DECLARATION.findAll(text).map { pkg + it.groupValues[1] }.toSet()
        return classes + (pkg + file.nameWithoutExtension + "Kt")
    }

    @Test
    fun noClassFromTheDebugSourceSetIsInTheReleaseVariant() {
        val debugClasses = classList("podplayer.classes.debug")
        val releaseClasses = classList("podplayer.classes.release")
        val debugFiles = sources(dir("podplayer.src.debug"))
        assertTrue("found the debug sources", debugFiles.isNotEmpty())
        // Release twins (BuildTypeSetup): the release source set declares the same name on purpose.
        val twins = sources(dir("podplayer.src.release")).flatMap(::declaredNames).toSet()

        val checked = mutableListOf<String>()
        for (file in debugFiles) {
            val names = declaredNames(file)
            // Each file's names must be real classes of the debug variant, so a wrong derivation can't pass.
            assertTrue("${file.name}: none of $names is a debug class", names.any { it in debugClasses })
            for (name in names.filter { it in debugClasses && it !in twins }) {
                checked += name
                val leaked = releaseClasses.filter { it == name || it.startsWith("$name\$") }
                assertEquals("$name (from ${file.name}) must not be in the release variant", emptyList<String>(), leaked)
            }
        }
        assertTrue("the Developer screen was among the checked names", checked.containsAll(DeveloperScreenNames.CLASSES))
        assertTrue("so was the debug StrictMode filter", "us.dikarya.podplayer.StrictModeFilter" in checked)
    }

    @Test
    fun noResourceFromTheDebugSourceSetIsInTheReleaseVariant() {
        val res = dir("podplayer.src.debug").resolve("res")
        val declared = mutableListOf<Pair<String, String>>()
        res.listFiles().orEmpty().filter { it.isDirectory }.forEach { typeDir ->
            val type = typeDir.name.substringBefore('-')
            typeDir.listFiles().orEmpty().forEach { file ->
                if (type == "values") {
                    VALUE.findAll(file.readText()).forEach { declared += it.groupValues[1].replace("-array", "") to it.groupValues[2] }
                } else {
                    declared += type to file.name.substringBefore('.')
                }
            }
        }
        assertTrue("found the debug resources", declared.isNotEmpty())
        for ((type, name) in declared) {
            val rClass = runCatching { Class.forName("${BuildConfig.APPLICATION_ID}.R\$$type") }.getOrNull() ?: continue
            val present = rClass.fields.any { it.name == name.replace('.', '_') }
            assertEquals("R.$type.$name (debug only) must not be in the release variant", false, present)
        }
    }

    private companion object {
        val PACKAGE = Regex("""(?m)^package\s+([\w.]+)""")

        /** Top-level (column 0) class, object and interface declarations, with any modifiers or annotations. */
        val DECLARATION = Regex(
            """(?m)^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|data|sealed|abstract|open|enum|annotation|value|inline|fun)\s+)*(?:class|object|interface)\s+(\w+)""",
        )
        val VALUE = Regex("""<(string|plurals|string-array|integer-array|array|color|dimen|bool|integer|style)\s+name="([^"]+)"""")
    }
}
