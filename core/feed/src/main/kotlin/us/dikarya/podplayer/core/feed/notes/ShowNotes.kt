/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.notes

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import us.dikarya.podplayer.core.feed.xml.Ticker
import us.dikarya.podplayer.core.net.UrlPolicy

class NotesLimits(
    /** Longer notes are refused; the feed parser already caps notes at [us.dikarya.podplayer.core.feed.FieldLimits.NOTES]. */
    val maxInputChars: Int = 1024 * 1024,
    val maxElements: Int = 50_000,
    val maxDuration: Duration = 2.seconds,
)

enum class NotesProblem { TooLarge, TooManyElements, TimeLimit }

sealed interface NotesResult {
    data class Ok(val text: RichText) : NotesResult

    /** The UI shows a plain message instead of the notes. */
    data class Rejected(val problem: NotesProblem) : NotesResult
}

/**
 * The show-notes sanitizer (SPEC §5). jsoup parses the HTML; a walk over the result keeps only an
 * allowlist of formatting (p, br, ul, ol, li, a[href], b, strong, i, em, blockquote, h1–h4 as bold) and turns it
 * into [RichText]. Scripts, styles, frames, forms, media, embedded objects, SVG, MathML, templates and images
 * are dropped together with their content; other unknown elements keep only their text. No attribute
 * survives except a link's destination, and only http, https and mailto links do. Remote images are never
 * shown in M1 (they are tracking pixels as often as content). Bare http(s) URLs in the text become links,
 * and timestamps become seek spans.
 */
object ShowNotes {
    private const val MAX_TREE_DEPTH = 256
    private const val TIME_CHECK_EVERY = 64

    private val dropped = setOf(
        "script", "style", "iframe", "frame", "frameset", "form", "input", "button", "select", "option", "optgroup",
        "textarea", "object", "embed", "applet", "param", "video", "audio", "source", "track", "svg", "math",
        "template", "noscript", "noembed", "noframes", "img", "picture", "canvas", "map", "area", "head", "title",
        "meta", "link", "base", "dialog", "portal", "slot",
    )
    private val paragraphBlocks = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "section", "article", "header",
        "footer", "main", "nav", "aside", "figure", "figcaption", "address", "pre", "hr", "dl", "details", "center",
    )
    private val lineBlocks = setOf("tr", "dt", "dd", "summary")
    private val bold = setOf("b", "strong", "h1", "h2", "h3", "h4")
    private val italic = setOf("i", "em")
    private val htmlTag = Regex("""<(?:[a-zA-Z][a-zA-Z0-9]*[\s/>]|/[a-zA-Z]|!--)""")

    fun sanitize(
        html: String?,
        durationMs: Long? = null,
        limits: NotesLimits = NotesLimits(),
        ticker: Ticker = Ticker.SYSTEM,
    ): NotesResult {
        if (html.isNullOrBlank()) return NotesResult.Ok(RichText.EMPTY)
        if (html.length > limits.maxInputChars) return NotesResult.Rejected(NotesProblem.TooLarge)
        val start = ticker.nanos()
        val budget = limits.maxDuration.inWholeNanoseconds
        val out = RichTextBuilder(durationMs)
        val overBudget = { ticker.nanos() - start > budget }
        if (!htmlTag.containsMatchIn(html)) {
            if (!plainText(html, out, overBudget)) return NotesResult.Rejected(NotesProblem.TimeLimit)
            val text = out.build()
            if (overBudget()) return NotesResult.Rejected(NotesProblem.TimeLimit)
            return NotesResult.Ok(text)
        }
        val body = Parser.htmlParser().setMaxDepth(MAX_TREE_DEPTH).parseInput(html, "").body()
        if (overBudget()) return NotesResult.Rejected(NotesProblem.TimeLimit)

        var elements = 0
        var nodes = 0
        var problem: NotesProblem? = null
        val walker = object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                if (++nodes % TIME_CHECK_EVERY == 0 && overBudget()) {
                    problem = NotesProblem.TimeLimit
                    return NodeFilter.FilterResult.STOP
                }
                when (node) {
                    is TextNode -> out.text(node.wholeText)
                    is Element -> {
                        if (node === body) return NodeFilter.FilterResult.CONTINUE
                        if (++elements > limits.maxElements) {
                            problem = NotesProblem.TooManyElements
                            return NodeFilter.FilterResult.STOP
                        }
                        val name = node.normalName()
                        if (name in dropped) return NodeFilter.FilterResult.SKIP_ENTIRELY
                        out.open(name, node)
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element && node !== body) out.close(node.normalName())
                return NodeFilter.FilterResult.CONTINUE
            }
        }
        NodeTraversor.filter(walker, body)
        problem?.let { return NotesResult.Rejected(it) }
        val text = out.build()
        if (overBudget()) return NotesResult.Rejected(NotesProblem.TimeLimit)
        return NotesResult.Ok(text)
    }

    /**
     * Notes without markup: keep the publisher's lines, a blank line starts a paragraph. Entities are still HTML's
     * (`&nbsp;`, `&amp;`): the field is HTML even when it has no tags. False when the time budget ran out.
     */
    private fun plainText(text: String, out: RichTextBuilder, overBudget: () -> Boolean): Boolean {
        for ((index, line) in text.replace("\r\n", "\n").replace('\r', '\n').split('\n').withIndex()) {
            if (index % TIME_CHECK_EVERY == 0 && overBudget()) return false
            if (line.isBlank()) {
                out.requestBreak(2)
            } else {
                out.requestBreak(1)
                out.text(if ('&' in line) Parser.unescapeEntities(line, false) else line)
            }
        }
        return true
    }

    /** The host a link goes to, from the parsed URL, or null when it isn't a usable link. */
    internal fun destination(url: String): String? {
        if (url.startsWith("mailto:", ignoreCase = true)) {
            val address = url.substring(7).substringBefore('?')
            return address.substringAfterLast('@', "").lowercase().takeIf { it.isNotEmpty() && '/' !in it }
        }
        return url.toHttpUrlOrNull()?.host
    }

    /** A cleaned, allowed link URL, or null. http(s) URLs come back in canonical form. */
    internal fun linkUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty() || !UrlPolicy.isAllowedLink(url)) return null
        if (url.startsWith("mailto:", ignoreCase = true)) return url.takeIf { destination(it) != null }
        return url.toHttpUrlOrNull()?.toString()
    }

    private class RichTextBuilder(private val durationMs: Long?) {
        private val sb = StringBuilder()
        private val spans = mutableListOf<RichSpan>()

        private class Open(val tag: String, val style: RichStyle, var start: Int = -1)

        private val open = ArrayList<Open>()
        private var pendingBreak = 0
        private var pendingSpace = false
        private var inLink = 0

        /** Lists: one entry per open ul/ol, holding the next number (0 for bullets). */
        private val lists = ArrayList<IntArray>()

        fun open(tag: String, element: Element) {
            when {
                tag == "br" -> requestBreak(1)
                tag == "ul" || tag == "ol" -> {
                    // A list nested in an item ends that item's own line.
                    closeStyle("li")
                    requestBreak(if (lists.isEmpty()) 2 else 1)
                    lists += intArrayOf(if (tag == "ol") 1 else 0)
                }
                tag == "li" -> {
                    requestBreak(1)
                    val counter = lists.lastOrNull()
                    val marker = when {
                        counter == null || counter[0] == 0 -> "• "
                        else -> "${counter[0]++}. "
                    }
                    open += Open("li", RichStyle.ListItem((lists.size - 1).coerceAtLeast(0)))
                    append(marker)
                }
                tag in paragraphBlocks -> requestBreak(2)
                tag in lineBlocks -> requestBreak(1)
                tag == "td" || tag == "th" -> pendingSpace = pendingSpace || sb.isNotEmpty()
            }
            if (tag in bold) open += Open(tag, RichStyle.Bold)
            if (tag in italic) open += Open(tag, RichStyle.Italic)
            if (tag == "blockquote") open += Open(tag, RichStyle.Quote)
            if (tag == "a") {
                val url = linkUrl(element.attr("href"))
                val destination = url?.let(::destination)
                if (url != null && destination != null) {
                    open += Open(tag, RichStyle.Link(url, destination))
                    inLink++
                }
            }
        }

        fun close(tag: String) {
            if (tag == "a" && open.any { it.tag == "a" }) inLink--
            if (tag in bold || tag in italic || tag == "blockquote" || tag == "a" || tag == "li") closeStyle(tag)
            when {
                tag == "ul" || tag == "ol" -> {
                    if (lists.isNotEmpty()) lists.removeAt(lists.size - 1)
                    requestBreak(if (lists.isEmpty()) 2 else 1)
                }
                tag == "li" -> requestBreak(1)
                tag in paragraphBlocks -> requestBreak(2)
                tag in lineBlocks -> requestBreak(1)
                tag == "td" || tag == "th" -> pendingSpace = true
            }
        }

        /** Closes the innermost open span for [tag]. */
        private fun closeStyle(tag: String) {
            val index = open.indexOfLast { it.tag == tag }
            if (index < 0) return
            val span = open.removeAt(index)
            if (span.start >= 0 && sb.length > span.start) spans += RichSpan(span.start, sb.length, span.style)
        }

        fun requestBreak(strength: Int) {
            if (strength > pendingBreak) pendingBreak = strength
        }

        /** Appends text with HTML whitespace rules: runs collapse to one space, breaks win over spaces. */
        fun text(raw: String) {
            if (raw.isEmpty()) return
            if (raw.first().isHtmlSpace()) pendingSpace = true
            val words = raw.split(htmlSpaces).filter { it.isNotEmpty() }
            if (words.isEmpty()) return
            val segment = words.joinToString(" ")
            val at = append(segment)
            if (inLink == 0) decorate(segment, at)
            if (raw.last().isHtmlSpace()) pendingSpace = true
        }

        /** Writes pending breaks or a pending space, starts spans waiting for text, then [text]. */
        private fun append(text: String): Int {
            if (sb.isNotEmpty()) {
                when {
                    pendingBreak > 0 -> repeat(pendingBreak) { sb.append('\n') }
                    pendingSpace -> sb.append(' ')
                }
            }
            pendingBreak = 0
            pendingSpace = false
            for (span in open) if (span.start < 0) span.start = sb.length
            val at = sb.length
            sb.append(text)
            return at
        }

        /** Bare URLs become links; timestamps outside them become seek spans. */
        private fun decorate(segment: String, offset: Int) {
            val linked = mutableListOf<IntRange>()
            for (m in bareUrl.findAll(segment)) {
                val trimmed = trimUrl(m.value)
                val url = linkUrl(trimmed) ?: continue
                val destination = destination(url) ?: continue
                val range = m.range.first until m.range.first + trimmed.length
                linked += range
                spans += RichSpan(offset + range.first, offset + range.last + 1, RichStyle.Link(url, destination))
            }
            for (found in Timestamps.find(segment, durationMs)) {
                if (linked.any { found.start in it || (found.end - 1) in it }) continue
                spans += RichSpan(offset + found.start, offset + found.end, RichStyle.Timestamp(found.positionMs))
            }
        }

        fun build(): RichText {
            // Spans still open at the end (unclosed tags) end with the text.
            for (span in open) if (span.start >= 0 && sb.length > span.start) spans += RichSpan(span.start, sb.length, span.style)
            open.clear()
            if (sb.isEmpty()) return RichText.EMPTY
            val sorted = spans.sortedWith(compareBy<RichSpan> { it.start }.thenByDescending { it.end })
            return RichText(sb.toString(), sorted)
        }

        private companion object {
            val htmlSpaces = Regex("[ \t\n\r\u000C]+")
            // \xA0 is the no-break space.
            val bareUrl = Regex("""(?i)\bhttps?://[^\s<>"'\xA0]+""")

            fun Char.isHtmlSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

            /**
             * Drops trailing punctuation that ends a sentence, and a closing bracket the URL didn't open.
             * Bracket counts are kept as running totals: a URL followed by a long run of brackets (hostile
             * notes can't be more than the input limit, but that is a million characters) trims in linear time.
             */
            fun trimUrl(url: String): String {
                var roundOpen = 0
                var roundClose = 0
                var squareOpen = 0
                var squareClose = 0
                for (c in url) {
                    when (c) {
                        '(' -> roundOpen++
                        ')' -> roundClose++
                        '[' -> squareOpen++
                        ']' -> squareClose++
                    }
                }
                var end = url.length
                while (end > 0) {
                    val c = url[end - 1]
                    val trim = when (c) {
                        '.', ',', ';', ':', '!', '?', '’', '”' -> true
                        ')' -> roundOpen < roundClose
                        ']' -> squareOpen < squareClose
                        else -> false
                    }
                    if (!trim) break
                    if (c == ')') roundClose-- else if (c == ']') squareClose--
                    end--
                }
                return url.substring(0, end)
            }
        }
    }
}
