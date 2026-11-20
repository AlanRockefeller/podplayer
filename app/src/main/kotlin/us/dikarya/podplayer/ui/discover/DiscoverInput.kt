/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

/** What the owner typed in Discover's one field (SPEC §12): search terms, or a feed address to preview. */
sealed interface DiscoverInput {
    data object Empty : DiscoverInput

    data class Search(val term: String) : DiscoverInput

    /** Still unchecked: the URL policy decides whether it can be used. */
    data class FeedUrl(val url: String) : DiscoverInput

    companion object {
        const val MAX_TERM = 200

        private val schemeAndSlashes = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        /** A host name ending in letters (`example.org`, `feeds.example.com/rss`), not a number like `99.9`. */
        private val bareHost = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,63}(:\d{1,5})?([/?#]\S*)?$""")

        fun of(raw: String): DiscoverInput {
            val text = raw.trim()
            return when {
                text.isEmpty() -> Empty
                text.none { it.isWhitespace() } && schemeAndSlashes.containsMatchIn(text) -> FeedUrl(text)
                text.none { it.isWhitespace() } && bareHost.matches(text) -> FeedUrl("https://$text")
                else -> Search(text.take(MAX_TERM).trim())
            }
        }
    }
}
