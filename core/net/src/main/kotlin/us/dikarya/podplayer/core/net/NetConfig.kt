/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Settings for [NetGate]. The defaults are SPEC §3; tests shorten the timeouts. */
class NetConfig(
    appVersion: String,
    val connectTimeout: Duration = 15.seconds,
    /** How long a single read may wait for bytes. There is no total-time limit on downloads. */
    val readTimeout: Duration = 30.seconds,
    val maxRedirects: Int = 5,
) {
    /** `Podplayer/<version> (Android)`. No other identifying headers are sent. */
    val userAgent: String = "Podplayer/${sanitizeVersion(appVersion)} (Android)"

    private companion object {
        fun sanitizeVersion(version: String): String =
            version.filter { it.isLetterOrDigit() && it.code < 128 || it in ".-_+" }.take(40).ifEmpty { "unknown" }
    }
}

private const val MIB = 1024L * 1024

/** Size and total-time limits for bodies read into memory (SPEC §3). Sizes count decompressed bytes. */
class BodyLimit(val maxBytes: Long, val maxDuration: Duration) {
    override fun toString() = "BodyLimit($maxBytes bytes, $maxDuration)"

    companion object {
        val FEED = BodyLimit(20 * MIB, 2.minutes)
        val OPML = BodyLimit(20 * MIB, 2.minutes)
        val CHAPTERS = BodyLimit(5 * MIB, 1.minutes)
        val TRANSCRIPT = BodyLimit(5 * MIB, 1.minutes)
        val IMAGE = BodyLimit(10 * MIB, 1.minutes)
    }
}

object NetLimits {
    /** Largest episode file the download engine accepts (SPEC §3: 2 GB). */
    const val MEDIA_MAX_BYTES: Long = 2048 * MIB
}
