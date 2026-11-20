/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.xml

/**
 * HTML named entities that publishers paste into feeds outside CDATA, where XML doesn't know them.
 * Only the common ones: anything else is treated as a bare ampersand and shows up as written.
 */
internal object HtmlEntities {
    private val table: Map<String, Int> = mapOf(
        "nbsp" to 160, "iexcl" to 161, "cent" to 162, "pound" to 163, "curren" to 164, "yen" to 165,
        "brvbar" to 166, "sect" to 167, "uml" to 168, "copy" to 169, "ordf" to 170, "laquo" to 171,
        "not" to 172, "shy" to 173, "reg" to 174, "macr" to 175, "deg" to 176, "plusmn" to 177,
        "sup2" to 178, "sup3" to 179, "acute" to 180, "micro" to 181, "para" to 182, "middot" to 183,
        "cedil" to 184, "sup1" to 185, "ordm" to 186, "raquo" to 187, "frac14" to 188, "frac12" to 189,
        "frac34" to 190, "iquest" to 191, "Agrave" to 192, "Aacute" to 193, "Acirc" to 194, "Atilde" to 195,
        "Auml" to 196, "Aring" to 197, "AElig" to 198, "Ccedil" to 199, "Egrave" to 200, "Eacute" to 201,
        "Ecirc" to 202, "Euml" to 203, "Igrave" to 204, "Iacute" to 205, "Icirc" to 206, "Iuml" to 207,
        "ETH" to 208, "Ntilde" to 209, "Ograve" to 210, "Oacute" to 211, "Ocirc" to 212, "Otilde" to 213,
        "Ouml" to 214, "times" to 215, "Oslash" to 216, "Ugrave" to 217, "Uacute" to 218, "Ucirc" to 219,
        "Uuml" to 220, "Yacute" to 221, "THORN" to 222, "szlig" to 223, "agrave" to 224, "aacute" to 225,
        "acirc" to 226, "atilde" to 227, "auml" to 228, "aring" to 229, "aelig" to 230, "ccedil" to 231,
        "egrave" to 232, "eacute" to 233, "ecirc" to 234, "euml" to 235, "igrave" to 236, "iacute" to 237,
        "icirc" to 238, "iuml" to 239, "eth" to 240, "ntilde" to 241, "ograve" to 242, "oacute" to 243,
        "ocirc" to 244, "otilde" to 245, "ouml" to 246, "divide" to 247, "oslash" to 248, "ugrave" to 249,
        "uacute" to 250, "ucirc" to 251, "uuml" to 252, "yacute" to 253, "thorn" to 254, "yuml" to 255,
        "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402,
        "circ" to 710, "tilde" to 732, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204,
        "zwj" to 8205, "lrm" to 8206, "rlm" to 8207, "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216,
        "rsquo" to 8217, "sbquo" to 8218, "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222,
        "dagger" to 8224, "Dagger" to 8225, "bull" to 8226, "hellip" to 8230, "permil" to 8240,
        "prime" to 8242, "Prime" to 8243, "lsaquo" to 8249, "rsaquo" to 8250, "euro" to 8364,
        "trade" to 8482, "larr" to 8592, "rarr" to 8594, "uarr" to 8593, "darr" to 8595, "hearts" to 9829,
    )

    /** The longest name in [table], so the scanner knows how far to look for the `;`. */
    val MAX_NAME_LENGTH: Int = table.keys.maxOf { it.length }

    fun codePoint(name: String): Int? = table[name]
}
