/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import okhttp3.Request
import okhttp3.Response

/**
 * ETag and Last-Modified from the last good response, for conditional GET (SPEC §3) and for
 * If-Range when resuming a download (SPEC §9). The data layer stores both strings.
 *
 * Values come from hostile servers. A value OkHttp would refuse to send (control or non-ASCII characters),
 * or one that is unreasonably long, is dropped here so it can never crash or inject into the next request.
 */
class Validators private constructor(val etag: String?, val lastModified: String?) {
    val isEmpty: Boolean get() = etag == null && lastModified == null

    /** A strong ETag, else Last-Modified, else null. Weak ETags (`W/"..."`) are not valid in If-Range. */
    val ifRangeValue: String?
        get() = etag?.takeUnless { it.startsWith("W/") } ?: lastModified

    internal fun applyConditions(builder: Request.Builder): Request.Builder = builder.apply {
        etag?.let { header("If-None-Match", it) }
        lastModified?.let { header("If-Modified-Since", it) }
    }

    override fun equals(other: Any?) = other is Validators && other.etag == etag && other.lastModified == lastModified

    override fun hashCode() = 31 * etag.hashCode() + lastModified.hashCode()

    override fun toString() = "Validators(etag=$etag, lastModified=$lastModified)"

    companion object {
        const val MAX_LENGTH = 256

        val None = Validators(null, null)

        fun of(etag: String?, lastModified: String?) = Validators(clean(etag), clean(lastModified))

        internal fun from(response: Response) = of(response.header("ETag"), response.header("Last-Modified"))

        private fun clean(value: String?): String? = value?.trim()?.takeIf { v ->
            v.isNotEmpty() && v.length <= MAX_LENGTH && v.all { it in ' '..'~' }
        }
    }
}
