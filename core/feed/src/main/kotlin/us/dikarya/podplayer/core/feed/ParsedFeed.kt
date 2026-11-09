/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import us.dikarya.podplayer.core.net.redact

/*
 * The parser's output: SPEC §4 "Fields to keep". Every URL in here has passed UrlPolicy.checkFeedUrl
 * (http or https, sane host), every string is trimmed and capped (FieldLimits), and blank values are null.
 * Notes and descriptions are raw HTML: they only ever reach the screen through ShowNotes.sanitize.
 */

enum class FeedFormat { Rss, Atom }

data class ParsedFeed(
    val format: FeedFormat,
    val title: String? = null,
    /** The show's website. */
    val link: String? = null,
    /** Raw HTML or text; render only through [us.dikarya.podplayer.core.feed.notes.ShowNotes]. */
    val description: String? = null,
    val author: String? = null,
    /** `itunes:image`, then `image/url` (Atom: `logo`, then `icon`). */
    val imageUrl: String? = null,
    val categories: List<String> = emptyList(),
    /** `podcast:guid`. */
    val podcastGuid: String? = null,
    val funding: List<Funding> = emptyList(),
    val persons: List<Person> = emptyList(),
    val license: License? = null,
    /** In document order (usually newest first). */
    val episodes: List<ParsedEpisode> = emptyList(),
)

data class ParsedEpisode(
    val guid: String? = null,
    val title: String? = null,
    /** Epoch milliseconds, or null when missing or unparseable (the caller uses the first-seen time). */
    val publishedAt: Long? = null,
    val enclosure: ParsedEnclosure? = null,
    val durationMs: Long? = null,
    /** `content:encoded`, else `description`, else `itunes:summary`. Raw; sanitize before showing. */
    val notes: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val chapters: Chapters? = null,
    /** Metadata only: never fetched or shown in standard playback (SPEC §4). */
    val transcripts: List<Transcript> = emptyList(),
    val soundbites: List<Soundbite> = emptyList(),
    /** Stored for later milestones. */
    val alternateEnclosures: List<AlternateEnclosure> = emptyList(),
)

/** [type] and [length] are hints only (SPEC §9): publishers mislabel both. */
data class ParsedEnclosure(val url: String, val type: String?, val length: Long?) {
    // Private feeds put tokens in enclosure URLs too.
    override fun toString() = "ParsedEnclosure(${redact(url)}, $type, $length)"
}

data class Funding(val url: String, val text: String?)

data class Person(val name: String, val role: String?, val group: String?, val imageUrl: String?, val link: String?)

data class License(val identifier: String?, val url: String?)

data class Chapters(val url: String, val type: String?) {
    override fun toString() = "Chapters(${redact(url)}, $type)"
}

data class Transcript(val url: String, val type: String?, val language: String?, val rel: String?) {
    override fun toString() = "Transcript(${redact(url)}, $type, $language, $rel)"
}

data class Soundbite(val startMs: Long, val durationMs: Long, val title: String?)

data class AlternateEnclosure(
    val type: String?,
    val length: Long?,
    val bitrate: Long?,
    val title: String?,
    val language: String?,
    val isDefault: Boolean,
    val sources: List<String>,
) {
    override fun toString() =
        "AlternateEnclosure($type, $length, $bitrate, $title, $language, $isDefault, ${sources.map(::redact)})"
}

/** Caps on what one feed may store. Longer values are cut and reported as [FeedWarning.FieldsTruncated]. */
object FieldLimits {
    const val TITLE = 1_000
    const val SHORT_TEXT = 2_000
    const val GUID = 2_000
    /** Show notes and descriptions. The sanitizer has its own, larger, input limit. */
    const val NOTES = 512 * 1024
    /** Categories, funding, persons, transcripts, soundbites, alternate enclosures and their sources. */
    const val LIST = 100
}

sealed interface FeedParseResult {
    data class Success(val feed: ParsedFeed, val warnings: List<FeedWarning>) : FeedParseResult

    data class Failure(val problem: ParseProblem) : FeedParseResult
}

/** Things the owner may want to know about a feed that still parsed. */
sealed interface FeedWarning {
    /** Publisher mistakes the pre-pass fixed (bare `&`, illegal characters, a DOCTYPE, missing namespaces). */
    data class Repaired(val repairs: us.dikarya.podplayer.core.feed.xml.Repairs) : FeedWarning

    /**
     * SPEC §4: duplicate GUIDs, a "broken feed". [guids] is how many GUIDs appear more than once.
     * [validatorLinks] are validator front pages. They deliberately don't carry the feed URL, which may hold
     * a private token (CLAUDE.md: feed URLs go nowhere but their own host); the owner pastes it in.
     */
    data class DuplicateGuids(val guids: Int, val validatorLinks: List<String> = VALIDATORS) : FeedWarning

    /** URLs refused by the URL policy (bad scheme, malformed, ambiguous host) and left out. */
    data class UrlsRejected(val count: Int) : FeedWarning

    /** Values cut to [FieldLimits]. */
    data class FieldsTruncated(val count: Int) : FeedWarning

    companion object {
        val VALIDATORS = listOf("https://validator.w3.org/feed/", "https://www.castfeedvalidator.com/")
    }
}
