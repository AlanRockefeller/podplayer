/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.xml

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Where the charset used for decoding came from. */
enum class EncodingSource {
    ByteOrderMark,
    XmlDeclaration,
    HttpHeader,

    /** Recognised from the bytes: UTF-16 without a BOM, or UTF-8 under a wrong single-byte declaration. */
    Sniffed,

    /** Nothing said anything usable: UTF-8, with replacement characters for invalid bytes. */
    Default,
}

class DecodedText(val text: String, val charset: Charset, val source: EncodingSource) {
    override fun toString() = "DecodedText(${text.length} chars, $charset from $source)"
}

/**
 * SPEC §4 encoding detection: byte order mark, then the XML declaration, then the HTTP `charset`, then
 * UTF-8 with replacement. Two repairs on top, both for common publisher mistakes:
 * - ISO-8859-1 and US-ASCII are decoded as windows-1252, as browsers do (curly quotes in "Latin-1" feeds).
 * - A single-byte declaration (or header) on bytes that are valid UTF-8 with multi-byte sequences is
 *   overruled: the template says Latin-1, the text is UTF-8. Real Latin-1 text almost never forms valid
 *   UTF-8 sequences.
 */
object EncodingDetector {
    private val windows1252: Charset = Charset.forName("windows-1252")
    private val utf32be: Charset? = charsetOrNull("UTF-32BE")
    private val utf32le: Charset? = charsetOrNull("UTF-32LE")

    private const val DECLARATION_WINDOW = 1024
    private const val MAX_CHARSET_NAME = 40

    private val declarationEncoding = Regex("""^<\?xml[^>]*?\bencoding\s*=\s*["']([^"']*)["']""")
    private val headerCharset = Regex("""(?i)\bcharset\s*=\s*["']?([^"';\s]*)""")
    private val charsetName = Regex("^[A-Za-z][A-Za-z0-9._:-]*$")

    fun decode(bytes: ByteArray, contentType: String?): DecodedText {
        bom(bytes)?.let { (charset, length) -> return decoded(bytes, length, charset, EncodingSource.ByteOrderMark) }
        utf16WithoutBom(bytes)?.let { return decoded(bytes, 0, it, EncodingSource.Sniffed) }

        val declared = declaredCharset(bytes)
        val fromHeader = contentType?.let(::charsetFromHeader)
        val (chosen, source) = when {
            declared != null -> declared to EncodingSource.XmlDeclaration
            fromHeader != null -> fromHeader to EncodingSource.HttpHeader
            else -> Charsets.UTF_8 to EncodingSource.Default
        }
        if (chosen != Charsets.UTF_8 && isSingleByte(chosen) && looksLikeUtf8(bytes)) {
            return decoded(bytes, 0, Charsets.UTF_8, EncodingSource.Sniffed)
        }
        return decoded(bytes, 0, chosen, source)
    }

    private fun decoded(bytes: ByteArray, offset: Int, charset: Charset, source: EncodingSource): DecodedText {
        // String(bytes, charset) replaces malformed input with U+FFFD; it never throws.
        val text = String(bytes, offset, bytes.size - offset, charset)
        return DecodedText(text, charset, source)
    }

    private fun bom(bytes: ByteArray): Pair<Charset, Int>? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Charsets.UTF_8 to 3
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF && utf32be != null -> utf32be to 4
            at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 && utf32le != null -> utf32le to 4
            at(0) == 0xFE && at(1) == 0xFF -> Charsets.UTF_16BE to 2
            at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16LE to 2
            else -> null
        }
    }

    /** `<?` or `<r` in UTF-16 without a byte order mark (XML spec, appendix F). */
    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        if (bytes.size < 4) return null
        val b = IntArray(4) { bytes[it].toInt() and 0xFF }
        return when {
            b[0] == 0x00 && b[1] == 0x3C && b[2] == 0x00 && b[3] != 0x00 -> Charsets.UTF_16BE
            b[0] == 0x3C && b[1] == 0x00 && b[2] != 0x00 && b[3] == 0x00 -> Charsets.UTF_16LE
            else -> null
        }
    }

    /** The declaration's encoding, read as ASCII. A UTF-16/32 declaration on single-byte text is a lie. */
    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, DECLARATION_WINDOW), Charsets.ISO_8859_1).trimStart()
        val name = declarationEncoding.find(head)?.groupValues?.get(1) ?: return null
        val charset = lookup(name) ?: return null
        if (charset.name().startsWith("UTF-16") || charset.name().startsWith("UTF-32")) return null
        return charset
    }

    private fun charsetFromHeader(contentType: String): Charset? {
        val name = headerCharset.find(contentType)?.groupValues?.get(1) ?: return null
        val charset = lookup(name) ?: return null
        // The bytes are not UTF-16 (no BOM and not sniffed), whatever the header says.
        if (charset.name().startsWith("UTF-16") || charset.name().startsWith("UTF-32")) return null
        return charset
    }

    private fun lookup(rawName: String): Charset? {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > MAX_CHARSET_NAME || !charsetName.matches(name)) return null
        val charset = charsetOrNull(name) ?: return null
        return when (charset) {
            Charsets.ISO_8859_1, Charsets.US_ASCII -> windows1252
            else -> charset
        }
    }

    private fun isSingleByte(charset: Charset): Boolean =
        charset == windows1252 || charset.name().startsWith("ISO-8859") || charset.name().startsWith("windows-125")

    /** Valid UTF-8 with at least one multi-byte sequence. */
    private fun looksLikeUtf8(bytes: ByteArray): Boolean {
        if (bytes.none { it < 0 }) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        null
    }
}
