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

    /** Only the sandbox package of :core:imagesandbox runs in the isolated process. */
    private val sandboxPackage = "src/main/kotlin/us/dikarya/podplayer/core/imagesandbox/sandbox/SandboxDecoder.kt"

    @Test
    fun imageDecodingIsAllowedInsideTheSandboxPackage() {
        assertEquals(emptySet<String>(), rules(sandbox, "BitmapFactoryUse.kt", sandboxPackage))
        assertEquals(emptySet<String>(), rules(sandbox, "ImageDecoderUse.kt", sandboxPackage))
        val test = "src/test/kotlin/us/dikarya/podplayer/core/imagesandbox/Fixtures.kt"
        assertEquals(emptySet<String>(), rules(sandbox, "BitmapFactoryUse.kt", test))
    }

    @Test
    fun imageDecodingIsRefusedInTheSandboxModulesMainProcessCode() {
        for (path in listOf(
            "src/main/kotlin/us/dikarya/podplayer/core/imagesandbox/client/SandboxClient.kt",
            "src/main/kotlin/us/dikarya/podplayer/core/imagesandbox/cache/ImageCache.kt",
            "src/main/kotlin/us/dikarya/podplayer/core/imagesandbox/ImageSandbox.kt",
            // A look-alike folder name elsewhere doesn't count.
            "src/main/kotlin/us/dikarya/podplayer/core/imagesandbox/client/sandbox/X.kt",
            "src/debug/kotlin/us/dikarya/podplayer/core/imagesandbox/sandbox/X.kt",
        )) {
            assertTrue(path, "image-decoding" in rules(sandbox, "BitmapFactoryUse.kt", path))
            assertTrue(path, "image-decoding" in rules(sandbox, "ImageDecoderUse.kt", path))
        }
    }

    @Test
    fun coilLoadersAreBuiltOnlyInTheSandboxModule() {
        for (context in listOf(app, playback)) {
            assertTrue(context.projectPath, "coil-loader-outside-imagesandbox" in rules(context, "CoilLoaderBuilder.kt"))
        }
        assertEquals(emptySet<String>(), rules(sandbox, "CoilLoaderBuilder.kt"))
    }

    @Test
    fun media3MayNotDecodeArtworkBytesOrFetchArtworkItself() {
        for (context in listOf(app, playback, sandbox)) {
            val found = ForbiddenApis.scan(context, mainKt, Samples.text("Media3UntrustedArtwork.kt"))
                .filter { it.ruleId == "media3-untrusted-artwork" }
                .map { it.match }
                .toSet()
            assertEquals(
                context.projectPath,
                setOf("DataSourceBitmapLoader", "setArtworkData", "maybeSetArtworkData", "SimpleBitmapLoader"),
                found,
            )
        }
    }

    @Test
    fun theSandboxedBitmapLoaderIsNotMistakenForAForbiddenOne() {
        assertEquals(emptySet<String>(), rules(playback, "AllowedArtworkLoader.kt"))
    }

    @Test
    fun otherHttpClientsAreRefused() {
        assertTrue("other-http-client" in rules(feed, "HttpUrlConnectionUse.kt"))
        assertTrue("other-http-client" in rules(app, "HttpUrlConnectionUse.kt"))
    }

    @Test
    fun media3sOwnHttpStackIsRefused() {
        // CodeRabbit review (PR 6): DefaultHttpDataSource runs on HttpURLConnection, outside the gate.
        for (context in listOf(app, playback)) {
            val found = ForbiddenApis.scan(context, mainKt, Samples.text("Media3UngatedHttp.kt"))
                .filter { it.ruleId == "media3-ungated-http" }
                .map { it.match }
                .toSet()
            assertEquals(context.projectPath, setOf("DefaultHttpDataSource", "DefaultDataSource", "DefaultMediaSourceFactory"), found)
        }
    }

    @Test
    fun theGatedOkHttpDataSourceIsAllowed() {
        assertEquals(emptySet<String>(), rules(playback, "GatedMedia3Http.kt"))
    }

    @Test
    fun okHttpClientIsRefusedOutsideTheNetModule() {
        val jobs = ModuleContext(projectPath = ":core:jobs", plainKotlin = false)
        for (context in listOf(app, playback, sandbox, feed, jobs)) {
            assertTrue(context.projectPath, "okhttp-client-outside-net" in rules(context, "OkHttpClientOutsideNet.kt"))
        }
    }

    @Test
    fun jsoupNetworkingIsRefusedEverywhereIncludingTheFeedModule() {
        for (context in listOf(app, playback, feed)) {
            assertTrue(context.projectPath, "jsoup-network" in rules(context, "JsoupConnect.kt"))
        }
    }

    @Test
    fun jsoupIsRefusedOutsideTheFeedModule() {
        for (context in listOf(app, playback, sandbox)) {
            assertTrue(context.projectPath, "jsoup-outside-feed" in rules(context, "JsoupParse.kt"))
        }
    }

    @Test
    fun jsoupParsingIsAllowedInTheFeedModule() {
        assertEquals(emptySet<String>(), rules(feed, "JsoupParse.kt"))
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
    fun fullyQualifiedAndroidAndAndroidxUseIsRefusedInPlainKotlinModules() {
        for (sample in listOf("AndroidFullyQualifiedInPlainModule.kt", "AndroidxImportInPlainModule.kt")) {
            assertTrue(sample, "android-in-plain-module" in rules(feed, sample))
            assertEquals(sample, emptySet<String>(), rules(playback, sample))
        }
    }

    @Test
    fun otherNetworkApisAreRefusedEverywhere() {
        val net = ModuleContext(projectPath = ":core:net", plainKotlin = true)
        for (context in listOf(app, playback, feed, net)) {
            for (sample in listOf("DownloadManagerUse.kt", "MediaPlayerUse.kt", "RawSocketUse.kt", "HttpUrlConnectionUse.kt")) {
                assertTrue("${context.projectPath} $sample", "other-network-apis" in rules(context, sample))
            }
        }
    }

    @Test
    fun otherImageDecodersAreRefusedOutsideTheSandboxPackage() {
        for (sample in listOf("BitmapDrawableFromStream.kt", "IconFromData.kt", "OtherImageDecoders.kt")) {
            assertTrue(sample, "image-decoding" in rules(app, sample))
            assertTrue(sample, "image-decoding" in rules(sandbox, sample))
            assertEquals(sample, emptySet<String>(), rules(sandbox, sample, sandboxPackage))
        }
    }

    @Test
    fun coilDecodersAreRefusedEverywhere() {
        for (context in listOf(app, playback, sandbox)) {
            assertTrue(context.projectPath, "coil-decoders" in rules(context, "CoilGifDecoder.kt"))
        }
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
    fun mainAndReleaseCodeMayNotNameTheDeveloperScreen() {
        assertTrue("developer-screen-outside-debug" in rules(app, "DeveloperScreenInMain.kt"))
        val release = "src/release/kotlin/us/dikarya/podplayer/BuildTypeSetup.kt"
        assertTrue("developer-screen-outside-debug" in rules(app, "DeveloperScreenInMain.kt", release))
        val layout = ForbiddenApis.scan(app, "src/main/res/layout/x.xml", "<TextView android:text=\"@string/dev_title\"/>")
        assertTrue(layout.any { it.ruleId == "developer-screen-outside-debug" })
    }

    @Test
    fun theDeveloperScreenIsFineInTheDebugSourceSetAndElsewhere() {
        val debug = "src/debug/kotlin/us/dikarya/podplayer/BuildTypeSetup.kt"
        assertEquals(emptySet<String>(), rules(app, "DeveloperScreenInMain.kt", debug))
        assertEquals(emptySet<String>(), rules(app, "DeveloperScreenInMain.kt", "src/testDebug/kotlin/us/dikarya/podplayer/T.kt"))
        assertEquals(emptySet<String>(), rules(playback, "DeveloperScreenInMain.kt"))
    }

    @Test
    fun onlySourceAndResourceFilesAreScanned() {
        assertEquals(setOf("kt", "java", "xml", "aidl"), ForbiddenApis.EXTENSIONS)
    }
}
