/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.core.feed.notes.RichText
import us.dikarya.podplayer.ui.theme.PodplayerColors

/**
 * Sanitized show notes as native Compose text (SPEC §5). A link doesn't open anything: tapping it calls
 * [onLink], and the screen shows the destination before the browser opens. A timestamp calls [onTimestamp] with
 * its position, or stays plain text when [onTimestamp] is null (a show's description has no episode to seek).
 */
fun RichText.toAnnotatedString(
    colors: PodplayerColors,
    onLink: (RichStyle.Link) -> Unit,
    onTimestamp: ((Long) -> Unit)?,
): AnnotatedString = buildAnnotatedString {
    append(text)
    val linkStyle = TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline))
    for ((index, span) in spans.withIndex()) {
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        if (start == end) continue
        when (val style = span.style) {
            RichStyle.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            RichStyle.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            // A span style, not a paragraph style: a quote can hold list items, and paragraph styles may not nest.
            RichStyle.Quote -> addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.textSecondary), start, end)
            is RichStyle.ListItem -> {
                val indent = (style.level.coerceIn(0, MAX_LEVEL) * LEVEL_INDENT).sp
                addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = (indent.value + HANGING).sp)), start, end)
            }
            is RichStyle.Link -> addLink(
                LinkAnnotation.Clickable("link-$index", linkStyle) { onLink(style) },
                start,
                end,
            )
            is RichStyle.Timestamp -> if (onTimestamp != null) {
                addLink(LinkAnnotation.Clickable("time-$index", linkStyle) { onTimestamp(style.positionMs) }, start, end)
            }
        }
    }
}

private const val LEVEL_INDENT = 16
private const val HANGING = 12f
private const val MAX_LEVEL = 8
