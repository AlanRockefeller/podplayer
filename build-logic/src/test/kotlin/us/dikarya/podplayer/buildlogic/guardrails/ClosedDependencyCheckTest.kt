/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClosedDependencyCheckTest {
    private fun banned(group: String, artifact: String) = ClosedDependencies.findBanned(group, artifact)

    @Test
    fun specNeverListGroupsAreBanned() {
        assertNotNull(banned("com.google.android.gms", "play-services-base"))
        assertNotNull(banned("com.google.firebase", "firebase-analytics"))
        assertNotNull(banned("com.google.android.play", "core"))
    }

    @Test
    fun subGroupsAreBanned() {
        assertNotNull(banned("com.google.android.gms.something", "x"))
        assertNotNull(banned("com.google.firebase.crashlytics", "x"))
    }

    @Test
    fun crashAnalyticsAndAdSdksAreBanned() {
        assertNotNull(banned("io.sentry", "sentry-android"))
        assertNotNull(banned("com.bugsnag", "bugsnag-android"))
        assertNotNull(banned("ch.acra", "acra-core"))
        assertNotNull(banned("com.applovin", "applovin-sdk"))
        assertNotNull(banned("com.google.android.ads", "mediation-test-suite"))
        assertNotNull(banned("com.facebook.android", "facebook-core"))
    }

    @Test
    fun webViewAndNativeMediaLibrariesAreBanned() {
        assertNotNull(banned("androidx.webkit", "webkit"))
        assertNotNull(banned("com.google.accompanist", "accompanist-webview"))
        assertNotNull(banned("com.facebook.fresco", "fresco"))
        assertNotNull(banned("com.arthenica", "ffmpeg-kit-full"))
        assertNotNull(banned("androidx.media3", "media3-decoder-ffmpeg"))
        assertNotNull(banned("androidx.media3", "media3-exoplayer-ima"))
    }

    @Test
    fun coilNetworkArtifactsAreBanned() {
        assertNotNull(banned("io.coil-kt.coil3", "coil-network-okhttp"))
        assertNotNull(banned("io.coil-kt.coil3", "coil-network-core"))
    }

    @Test
    fun coilDecoderArtifactsAreBanned() {
        // They decode GIF, SVG and video bytes in the app process; only the sandbox decodes untrusted images.
        assertNotNull(banned("io.coil-kt.coil3", "coil-gif"))
        assertNotNull(banned("io.coil-kt.coil3", "coil-svg"))
        assertNotNull(banned("io.coil-kt.coil3", "coil-video"))
    }

    @Test
    fun allowedLibrariesAndLookAlikesPass() {
        assertNull(banned("com.google.android.material", "material"))
        assertNull(banned("com.google.guava", "guava"))
        assertNull(banned("com.google.android.gmsx", "lookalike"))
        assertNull(banned("androidx.media3", "media3-exoplayer"))
        assertNull(banned("androidx.media3", "media3-session"))
        assertNull(banned("androidx.media3", "media3-datasource-okhttp"))
        assertNull(banned("io.coil-kt.coil3", "coil-compose"))
        assertNull(banned("io.coil-kt.coil3", "coil-core"))
        assertNull(banned("com.google.accompanist", "accompanist-permissions"))
        assertNull(banned("com.squareup.okhttp3", "okhttp"))
        assertNull(banned("org.jsoup", "jsoup"))
    }
}
