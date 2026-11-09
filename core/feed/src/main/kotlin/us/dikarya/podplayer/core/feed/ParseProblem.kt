/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

/**
 * Why a feed or OPML document was refused. The caller keeps the last good data and logs [logReason]
 * (CLAUDE.md: on a limit breach, reject, keep the last good data, log why). User-facing text lives in `:app`.
 * Nothing in here carries document content or URLs.
 */
sealed interface ParseProblem {
    val logReason: String

    data class TooLarge(val limitBytes: Int) : ParseProblem {
        override val logReason get() = "document larger than $limitBytes bytes"
    }

    data class TooManyItems(val limit: Int) : ParseProblem {
        override val logReason get() = "more than $limit items"
    }

    data class TooDeep(val limit: Int) : ParseProblem {
        override val logReason get() = "elements nested deeper than $limit"
    }

    data class TooManyAttributes(val limit: Int) : ParseProblem {
        override val logReason get() = "a tag with more than $limit attributes"
    }

    data object TimeLimit : ParseProblem {
        override val logReason get() = "parsing took longer than the time limit"
    }

    /** A DOCTYPE (or a stray declaration) that declares entities: expansion bombs and external entities. */
    data object EntityDeclaration : ParseProblem {
        override val logReason get() = "the document declares XML entities"
    }

    /** "The server returned a web page" (SPEC §4). */
    data object HtmlPage : ParseProblem {
        override val logReason get() = "the server returned a web page, not a feed"
    }

    /** Well-formed XML, but not RSS or Atom (or not OPML). [rootElement] is sanitized and short. */
    data class NotAFeed(val rootElement: String) : ParseProblem {
        override val logReason get() = "unexpected root element <$rootElement>"
    }

    /** Not well-formed XML even after the repairs. Position only; parser messages may quote content. */
    data class Malformed(val line: Int, val column: Int) : ParseProblem {
        override val logReason get() = "not well-formed XML at line $line, column $column"
    }
}
