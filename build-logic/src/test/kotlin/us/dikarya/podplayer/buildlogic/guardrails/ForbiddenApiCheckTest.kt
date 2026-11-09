/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForbiddenApiCheckTest {
    private val app = ModuleContext(projectPath = ":app", plainKotlin = false)
    private val playback = ModuleContext(projectPath = ":playback", plainKotlin = false)
    private val sandbox = ModuleContext(projectPath = ":core:imagesandbox", plainKotlin = false)
    private val feed = ModuleContext(projectPath = ":core:feed", plainKotlin = true)

    private val mainKt = "src/main/kotlin/us/dikarya/podplayer/Sample.kt"

    private fun rules(context: ModuleContext, sample: String, path: String = mainKt): Set<String> =
        ForbiddenApis.scan(context, path, Samples.text(sample)).map { it.ruleId }.toSet()

    @Test
    fun webViewImportIsRefusedEverywhere() {
        for (context in listOf(app, playback, sandbox, feed)) {
            assertTrue(context.projectPath, "webview" in rules(context, "WebViewImport.kt"))
        }
    }

    @Test
    fun webViewInALayoutIsRefused() {
        val found = rules(app, "WebViewLayout.xml", "src/main/res/layout/notes.xml")
        assertTrue("webview" in found)
    }

    @Test
    fun mediaExtractorIsRefusedEverywhere() {
        assertTrue("media-extractor" in rules(playback, "MediaExtractorUse.kt"))
        assertTrue("media-extractor" in rules(sandbox, "MediaExtractorUse.kt"))
    }

    @Test
    fun mediaMetadataRetrieverIsRefusedEverywhere() {
        assertTrue("media-metadata-retriever" in rules(playback, "MediaMetadataRetrieverUse.kt"))
        assertTrue("media-metadata-retriever" in rules(sandbox, "MediaMetadataRetrieverUse.kt"))
    }

    @Test
    fun imageDecodingIsRefusedOutsideTheSandbox() {
        assertTrue("image-decoding" in rules(app, "BitmapFactoryUse.kt"))
        assertTrue("image-decoding" in rules(playback, "ImageDecoderUse.kt"))
    }

    @Test
    fun imageDecodingIsAllowedInsideTheSandbox() {
        assertEquals(emptySet<String>(), rules(sandbox, "BitmapFactoryUse.kt"))
        assertEquals(emptySet<String>(), rules(sandbox, "ImageDecoderUse.kt"))
    }

    @Test
    fun otherHttpClientsAreRefused() {
        assertTrue("other-http-client" in rules(feed, "HttpUrlConnectionUse.kt"))
        assertTrue("other-http-client" in rules(app, "HttpUrlConnectionUse.kt"))
    }

    @Test
    fun okHttpClientIsRefusedOutsideTheNetModule() {
        val jobs = ModuleContext(projectPath = ":core:jobs", plainKotlin = false)
        for (context in listOf(app, playback, sandbox, feed, jobs)) {
            assertTrue(context.projectPath, "okhttp-client-outside-net" in rules(context, "OkHttpClientOutsideNet.kt"))
        }
    }

    @Test
    fun okHttpClientIsAllowedInTheNetModule() {
        val net = ModuleContext(projectPath = ":core:net", plainKotlin = true)
        assertEquals(emptySet<String>(), rules(net, "OkHttpClientOutsideNet.kt"))
    }

    @Test
    fun coilNetworkingIsRefused() {
        assertTrue("coil-network" in rules(app, "CoilNetwork.kt"))
        assertTrue("coil-network" in rules(sandbox, "CoilNetwork.kt"))
    }

    @Test
    fun androidImportsAreRefusedInPlainKotlinModules() {
        assertTrue("android-in-plain-module" in rules(feed, "AndroidImportInPlainModule.kt"))
    }

    @Test
    fun androidImportsAreFineInAndroidModules() {
        assertEquals(emptySet<String>(), rules(playback, "AndroidImportInPlainModule.kt"))
    }

    @Test
    fun hardCodedColorsAreRefusedInAppScreens() {
        val screen = "src/main/kotlin/us/dikarya/podplayer/ui/home/HomeScreen.kt"
        assertTrue("hard-coded-color" in rules(app, "HardCodedColor.kt", screen))
    }

    @Test
    fun hardCodedColorsAreAllowedInTheThemePackage() {
        val tokens = "src/main/kotlin/us/dikarya/podplayer/ui/theme/Tokens.kt"
        assertEquals(emptySet<String>(), rules(app, "HardCodedColor.kt", tokens))
    }

    @Test
    fun media3ExtractorsAreNotMistakenForPlatformOnes() {
        assertEquals(emptySet<String>(), rules(playback, "AllowedMedia3.kt"))
    }

    @Test
    fun cleanCodeHasNoViolations() {
        for (context in listOf(app, playback, sandbox, feed)) {
            assertEquals(emptyList<Violation>(), ForbiddenApis.scan(context, mainKt, Samples.text("Good.kt")))
        }
    }

    @Test
    fun violationsReportTheLineNumber() {
        val violation = ForbiddenApis.scan(app, mainKt, Samples.text("WebViewImport.kt")).first()
        assertEquals(9, violation.line)
        assertEquals(mainKt, violation.file)
    }

    @Test
    fun onlySourceAndResourceFilesAreScanned() {
        assertEquals(setOf("kt", "java", "xml", "aidl"), ForbiddenApis.EXTENSIONS)
    }
}
