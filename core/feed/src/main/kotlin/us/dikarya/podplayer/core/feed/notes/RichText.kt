/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.notes

/**
 * Sanitized show notes (SPEC §5): plain text plus style spans, the neutral model that `:app` turns into an
 * `AnnotatedString`. Paragraphs are separated by a blank line, line breaks are `\n`, and list items start
 * with "• " or "1. ". Spans never overlap partially; a span may contain another (a link inside bold text).
 */
data class RichText(val text: String, val spans: List<RichSpan>) {
    companion object {
        val EMPTY = RichText("", emptyList())
    }
}

/** [start] inclusive, [end] exclusive, in UTF-16 units of [RichText.text]. */
data class RichSpan(val start: Int, val end: Int, val style: RichStyle)

sealed interface RichStyle {
    data object Bold : RichStyle

    data object Italic : RichStyle

    /** A blockquote paragraph. */
    data object Quote : RichStyle

    /** One list item's own line, including its marker. [level] is 0 for a top-level list. */
    data class ListItem(val level: Int) : RichStyle

    /**
     * An http, https or mailto link. [destination] is the host (or mail domain) the UI shows before opening
     * the browser (SPEC §5); it is taken from the parsed URL, so `https://bank@evil/` shows "evil".
     */
    data class Link(val url: String, val destination: String) : RichStyle

    /** A timestamp in the notes: a seek within the same episode. */
    data class Timestamp(val positionMs: Long) : RichStyle
}
