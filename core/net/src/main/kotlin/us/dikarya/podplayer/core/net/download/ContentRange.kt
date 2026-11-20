/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net.download

/**
 * A parsed `Content-Range` response header: `bytes first-last/total`, `bytes first-last/ *` or `bytes * /total`
 * (RFC 9110 §14.4). [first] and [last] are null for the unsatisfied form sent with 416.
 */
internal data class ContentRange(val first: Long?, val last: Long?, val total: Long?) {
    companion object {
        private val satisfied = Regex("""^bytes (\d{1,18})-(\d{1,18})/(\d{1,18}|\*)$""")
        private val unsatisfied = Regex("""^bytes \*/(\d{1,18})$""")

        fun parse(header: String?): ContentRange? {
            val value = header?.trim()?.takeIf { it.length <= 100 } ?: return null
            unsatisfied.matchEntire(value)?.let { return ContentRange(null, null, it.groupValues[1].toLong()) }
            val match = satisfied.matchEntire(value) ?: return null
            val first = match.groupValues[1].toLong()
            val last = match.groupValues[2].toLong()
            val total = match.groupValues[3].takeIf { it != "*" }?.toLong()
            if (last < first) return null
            if (total != null && last >= total) return null
            return ContentRange(first, last, total)
        }
    }
}
