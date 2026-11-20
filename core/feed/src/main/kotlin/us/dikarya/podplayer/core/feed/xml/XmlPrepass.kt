/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.xml

import java.nio.charset.Charset
import us.dikarya.podplayer.core.feed.ParseProblem

/** What the pre-pass had to fix. Reported as a feed warning, so the refresh log can say so. */
data class Repairs(
    /** Characters illegal in XML 1.0, and character references to them, that were removed. */
    val illegalCharacters: Int = 0,
    /** `&` that started no valid reference and were escaped. */
    val bareAmpersands: Int = 0,
    /** HTML named entities (`&nbsp;`) turned into character references, and HTML-only `&#X26;` spelt `&#x26;`. */
    val htmlEntities: Int = 0,
    /** A DOCTYPE without entity declarations was removed. */
    val doctypeRemoved: Boolean = false,
    /** Well-known prefixes (`itunes`) used without a namespace declaration, declared on the root. */
    val namespacesAdded: List<String> = emptyList(),
) {
    val any: Boolean get() = this != NONE

    companion object {
        val NONE = Repairs()
    }
}

sealed interface PrepassResult {
    /** [xml] is ready for the pull parser: no DOCTYPE, no XML declaration, only XML-legal characters. */
    class Ready(
        val xml: String,
        val repairs: Repairs,
        val charset: Charset? = null,
        val encodingSource: EncodingSource? = null,
    ) : PrepassResult {
        override fun toString() = "Ready(${xml.length} chars, $repairs, $charset from $encodingSource)"
    }

    data class Rejected(val problem: ParseProblem) : PrepassResult
}

/**
 * SPEC §4 pre-pass, run before any XML parser sees the document:
 * 1. byte limit, then decoding ([EncodingDetector]);
 * 2. the prolog: the XML declaration is dropped (the text is already decoded), a DOCTYPE that declares
 *    entities (or uses parameter entities) rejects the document, any other DOCTYPE is dropped, and a web page
 *    is recognised by its DOCTYPE or root element;
 * 3. the body: characters illegal in XML 1.0 are removed (also inside CDATA), character references to them
 *    are dropped, `&` that starts no valid reference is escaped, common HTML entities become character
 *    references, and markup declarations outside the prolog are refused. CDATA, comments and processing
 *    instructions are otherwise copied as they are;
 * 4. tags with more attributes than [XmlLimits.maxAttributesPerTag] are refused;
 * 5. well-known prefixes used without a declaration get one on the root element.
 */
object XmlPrepass {
    private const val TIME_CHECK_MASK = 0xFFFF
    private const val MAX_REFERENCE_LENGTH = 32
    private const val MAX_DECLARATION_SCAN = 1024 * 1024
    private const val NO_QUOTE = '\u0000'

    private val xmlEntities = setOf("amp", "lt", "gt", "quot", "apos")
    private val htmlRoots = setOf("html", "head", "body", "meta", "title", "script", "div", "p", "h1", "center")

    fun run(bytes: ByteArray, contentType: String?, limits: XmlLimits, ticker: Ticker): PrepassResult =
        run(bytes, contentType, limits, Deadline(ticker, limits.maxDuration))

    fun clean(text: String, limits: XmlLimits, ticker: Ticker): PrepassResult =
        clean(text, limits, Deadline(ticker, limits.maxDuration))

    internal fun run(bytes: ByteArray, contentType: String?, limits: XmlLimits, deadline: Deadline): PrepassResult {
        if (bytes.size > limits.maxBytes) return PrepassResult.Rejected(ParseProblem.TooLarge(limits.maxBytes))
        val decoded = EncodingDetector.decode(bytes, contentType)
        return when (val result = clean(decoded.text, limits, deadline)) {
            is PrepassResult.Ready -> PrepassResult.Ready(result.xml, result.repairs, decoded.charset, decoded.source)
            is PrepassResult.Rejected -> result
        }
    }

    internal fun clean(text: String, limits: XmlLimits, deadline: Deadline): PrepassResult = try {
        Scanner(text, limits, deadline).run()
    } catch (e: XmlLimitException) {
        PrepassResult.Rejected(e.problem)
    }

    private class Scanner(private val text: String, private val limits: XmlLimits, private val deadline: Deadline) {
        private val out = StringBuilder(text.length + 256)
        private var i = 0
        private var steps = 0
        private var illegal = 0
        private var ampersands = 0
        private var entities = 0
        private var doctypeRemoved = false

        fun run(): PrepassResult {
            if (text.isNotEmpty() && text[0].code == BYTE_ORDER_MARK) i = 1
            prolog()
            val rootStart = out.length
            val rootName = rootName()
            if (rootName.lowercase() in htmlRoots) reject(ParseProblem.HtmlPage)
            body()
            val added = declareMissingNamespaces(rootStart + 1 + rootName.length)
            val repairs = Repairs(illegal, ampersands, entities, doctypeRemoved, added)
            return PrepassResult.Ready(out.toString(), repairs)
        }

        // --- Prolog -------------------------------------------------------------------------------

        private fun prolog() {
            var first = true
            while (true) {
                tick()
                while (i < text.length && text[i].isXmlWhitespace()) out.append(text[i++])
                when {
                    i >= text.length -> reject(malformedAt(i))
                    first && text.startsWith("<?xml", i) && isDeclarationEnd(i + 5) -> {
                        i = indexAfter("?>", i) ?: reject(malformedAt(i))
                    }
                    text.startsWith("<?", i) -> copyThrough("?>")
                    text.startsWith("<!--", i) -> copyThrough("-->")
                    text.regionMatches(i, "<!DOCTYPE", 0, 9, ignoreCase = true) -> doctype()
                    text[i] == '<' -> return
                    else -> reject(malformedAt(i))
                }
                first = false
            }
        }

        private fun isDeclarationEnd(at: Int) = at < text.length && (text[at].isXmlWhitespace() || text[at] == '?')

        /** Reads a DOCTYPE to its end. Refuses entity declarations and parameter entities; drops the rest. */
        private fun doctype() {
            val start = i
            var j = i + 9
            var quote = NO_QUOTE
            var inSubset = false
            var subsetStart = -1
            var subsetEnd = -1
            while (true) {
                if (j >= text.length) reject(malformedAt(start))
                val c = text[j]
                when {
                    quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                    c == '"' || c == '\'' -> quote = c
                    c == '[' && !inSubset -> {
                        inSubset = true
                        subsetStart = j
                    }
                    c == ']' && inSubset -> {
                        inSubset = false
                        subsetEnd = j
                    }
                    c == '%' && inSubset -> reject(ParseProblem.EntityDeclaration)
                    c == '>' && !inSubset -> break
                }
                j++
                if ((j and TIME_CHECK_MASK) == 0) deadline.check()
            }
            if (subsetStart >= 0) {
                val subset = text.substring(subsetStart, if (subsetEnd >= 0) subsetEnd else j)
                if (subset.contains("<!ENTITY", ignoreCase = true)) reject(ParseProblem.EntityDeclaration)
            }
            val name = text.substring(start + 9, j).trimStart().takeWhile { !it.isXmlWhitespace() && it != '[' && it != '>' }
            if (name.equals("html", ignoreCase = true)) reject(ParseProblem.HtmlPage)
            doctypeRemoved = true
            i = j + 1
        }

        private fun rootName(): String {
            var j = i + 1
            while (j < text.length && !text[j].isXmlWhitespace() && text[j] != '>' && text[j] != '/' && j - i < 256) j++
            return text.substring(i + 1, j)
        }

        // --- Body ---------------------------------------------------------------------------------

        private fun body() {
            var inTag = false
            var quote = NO_QUOTE
            var attributes = 0
            while (i < text.length) {
                tick()
                val c = text[i]
                when {
                    c == '&' -> reference()
                    inTag -> {
                        when {
                            quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                            c == '"' || c == '\'' -> quote = c
                            c == '=' -> if (++attributes > limits.maxAttributesPerTag) {
                                reject(ParseProblem.TooManyAttributes(limits.maxAttributesPerTag))
                            }
                            c == '>' -> inTag = false
                            c == '<' -> {
                                // A broken tag; let the parser report it.
                                inTag = false
                                continue
                            }
                        }
                        copyChar()
                    }
                    c == '<' -> when {
                        text.startsWith("<![CDATA[", i) -> copyThrough("]]>")
                        text.startsWith("<!--", i) -> copyThrough("-->")
                        text.startsWith("<?", i) -> copyThrough("?>")
                        text.startsWith("<!", i) -> markupDeclaration()
                        else -> {
                            inTag = true
                            quote = NO_QUOTE
                            attributes = 0
                            copyChar()
                        }
                    }
                    else -> copyChar()
                }
            }
        }

        /** `<!ENTITY`, `<!DOCTYPE`, `<!ELEMENT` and friends after the prolog. */
        private fun markupDeclaration(): Nothing {
            val limit = minOf(text.length, i + MAX_DECLARATION_SCAN)
            val close = text.indexOf('>', i).let { if (it < 0 || it > limit) limit else it }
            val bracket = text.indexOf('[', i).takeIf { it in 0 until close }
            val end = if (bracket != null) text.indexOf("]>", bracket).let { if (it < 0 || it > limit) limit else it } else close
            val declaration = text.substring(i, end)
            if (declaration.contains("ENTITY", ignoreCase = true)) reject(ParseProblem.EntityDeclaration)
            reject(malformedAt(i))
        }

        /** At `&`: keep valid references, drop ones to illegal characters, convert HTML ones, escape the rest. */
        private fun reference() {
            val semicolon = findSemicolon(i + 1)
            if (semicolon != null) {
                val body = text.substring(i + 1, semicolon)
                if (body.startsWith('#')) {
                    when (val codePoint = numericReference(body)) {
                        null -> Unit // malformed: escaped below
                        else -> {
                            if (codePoint in 0..0x10FFFF && isLegalCodePoint(codePoint.toInt())) {
                                if (body[1] == 'X') {
                                    // HTML accepts `&#X26;`; XML only `&#x26;`, and pull parsers reject the document.
                                    out.append("&#x").append(body, 2, body.length).append(';')
                                    entities++
                                } else {
                                    out.append(text, i, semicolon + 1)
                                }
                            } else {
                                illegal++
                            }
                            i = semicolon + 1
                            return
                        }
                    }
                } else if (body in xmlEntities) {
                    out.append(text, i, semicolon + 1)
                    i = semicolon + 1
                    return
                } else {
                    val codePoint = HtmlEntities.codePoint(body)
                    if (codePoint != null) {
                        out.append("&#").append(codePoint).append(';')
                        entities++
                        i = semicolon + 1
                        return
                    }
                }
            }
            out.append("&amp;")
            ampersands++
            i++
        }

        /** The `;` that closes a reference starting at [from], if the characters before it can be a name. */
        private fun findSemicolon(from: Int): Int? {
            var j = from
            while (j < text.length && j - from <= MAX_REFERENCE_LENGTH) {
                val c = text[j]
                if (c == ';') return if (j > from) j else null
                if (!(c.isLetterOrDigitAscii() || c == '#')) return null
                j++
            }
            return null
        }

        /** `#123` or `#x1F`: the code point, or -1 when it is too big to be one, or null when malformed. */
        private fun numericReference(body: String): Long? {
            val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
            val digits = body.substring(if (hex) 2 else 1)
            if (digits.isEmpty()) return null
            if (!digits.all { if (hex) it.isHexDigitAscii() else it in '0'..'9' }) return null
            if (digits.trimStart('0').length > 8) return -1
            return digits.toLong(if (hex) 16 else 10)
        }

        // --- Copying ------------------------------------------------------------------------------

        /** Copies one character, or a valid surrogate pair, dropping anything illegal in XML. */
        private fun copyChar() {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                out.append(c).append(text[i + 1])
                i += 2
                return
            }
            if (isLegalChar(c)) out.append(c) else illegal++
            i++
        }

        /** Copies from here through [terminator] (or to the end, which the parser then reports). */
        private fun copyThrough(terminator: String) {
            val end = text.indexOf(terminator, i + 2).let { if (it < 0) text.length else it + terminator.length }
            while (i < end) {
                copyChar()
                tick()
            }
        }

        private fun indexAfter(terminator: String, from: Int): Int? =
            text.indexOf(terminator, from).takeIf { it >= 0 }?.plus(terminator.length)

        private fun declareMissingNamespaces(at: Int): List<String> {
            val missing = Namespaces.wellKnownPrefixes.filter { (prefix, _) ->
                (out.indexOf("<$prefix:") >= 0 || out.indexOf("</$prefix:") >= 0) &&
                    out.indexOf("xmlns:$prefix=") < 0 && out.indexOf("xmlns:$prefix ") < 0
            }
            if (missing.isEmpty()) return emptyList()
            val declarations = missing.joinToString("") { (prefix, uri) -> " xmlns:$prefix=\"$uri\"" }
            out.insert(at.coerceAtMost(out.length), declarations)
            return missing.map { it.first }
        }

        private fun tick() {
            if ((++steps and TIME_CHECK_MASK) == 0) deadline.check()
        }

        private fun malformedAt(index: Int): ParseProblem.Malformed {
            var line = 1
            var lineStart = 0
            for (k in 0 until minOf(index, text.length)) {
                if (text[k] == '\n') {
                    line++
                    lineStart = k + 1
                }
            }
            return ParseProblem.Malformed(line, index - lineStart + 1)
        }

        private fun reject(problem: ParseProblem): Nothing = throw XmlLimitException(problem)
    }
}

internal fun Char.isXmlWhitespace() = this == ' ' || this == '\t' || this == '\n' || this == '\r'

private fun Char.isLetterOrDigitAscii() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isHexDigitAscii() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** XML 1.0 `Char`, for a single UTF-16 unit (surrogates are handled in pairs by the caller). */
internal fun isLegalChar(c: Char): Boolean =
    c.code == 0x9 || c.code == 0xA || c.code == 0xD || c.code in 0x20..0xD7FF || c.code in 0xE000..0xFFFD

/** U+FEFF at the start of decoded text. Spelled as a number so the source holds no invisible character. */
internal const val BYTE_ORDER_MARK = 0xFEFF

internal fun isLegalCodePoint(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
