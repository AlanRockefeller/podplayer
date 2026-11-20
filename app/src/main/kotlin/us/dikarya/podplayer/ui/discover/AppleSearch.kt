/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.redact

/** A GET through the gate ([us.dikarya.podplayer.core.net.NetGate.fetch]); a function so tests can answer it. */
fun interface GateFetch {
    suspend fun fetch(url: HttpUrl, limit: BodyLimit, policy: AddressPolicy): FetchResult
}

/** One show from a search. [feedUrl] already passed the URL policy. */
data class SearchHit(val feedUrl: String, val title: String, val publisher: String?, val artworkUrl: String?) {
    /** SPEC §3: http feeds are allowed and shown as not secure. */
    val isSecure: Boolean get() = feedUrl.startsWith("https://", ignoreCase = true)

    override fun toString() = "SearchHit(${redact(feedUrl)}, $title)"
}

sealed interface SearchOutcome {
    data class Results(val hits: List<SearchHit>) : SearchOutcome

    data class Failed(val problem: DiscoverProblem) : SearchOutcome
}

/**
 * Apple's podcast search (SPEC §12), the only search the app makes, and only when the owner searches
 * (CLAUDE.md "Privacy"). The request carries the term and nothing else, goes through the gate with no private
 * address approved, and the answer is treated as hostile like any feed: size and time limits, every feed URL
 * through the URL policy, text cleaned and capped.
 */
class AppleSearch(private val gate: GateFetch) {
    suspend fun search(term: String): SearchOutcome {
        val cleaned = term.trim().take(DiscoverInput.MAX_TERM)
        if (cleaned.isEmpty()) return SearchOutcome.Results(emptyList())
        return get(url(cleaned))
    }

    /** A podcasts.apple.com link's show, by its id (Apple's lookup, same host and limits as search). */
    suspend fun lookup(id: Long): SearchOutcome = get(lookupUrl(id))

    private suspend fun get(url: HttpUrl): SearchOutcome = when (val result = gate.fetch(url, LIMIT, AddressPolicy.Default)) {
        is FetchResult.Failure -> SearchOutcome.Failed(DiscoverProblem.Net(result.error))
        is FetchResult.NotModified -> SearchOutcome.Failed(DiscoverProblem.BadSearchResponse)
        is FetchResult.Success -> parse(result.body)
    }

    private fun parse(body: ByteArray): SearchOutcome {
        val response = try {
            json.decodeFromString(AppleResponse.serializer(), body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            // SerializationException and IllegalArgumentException for anything that isn't the expected JSON.
            return SearchOutcome.Failed(DiscoverProblem.BadSearchResponse)
        }
        val seen = HashSet<String>()
        val hits = response.results.asSequence().mapNotNull { result ->
            val feed = result.feedUrl?.let { UrlPolicy.checkFeedUrl(it) as? UrlCheck.Allowed }?.url?.toString() ?: return@mapNotNull null
            if (!seen.add(feed)) return@mapNotNull null
            val title = clean(result.collectionName ?: result.trackName) ?: return@mapNotNull null
            SearchHit(
                feedUrl = feed,
                title = title,
                publisher = clean(result.artistName),
                artworkUrl = (result.artworkUrl600 ?: result.artworkUrl100)?.takeIf { UrlPolicy.checkFeedUrl(it) is UrlCheck.Allowed },
            )
        }.take(MAX_RESULTS).toList()
        return SearchOutcome.Results(hits)
    }

    @Serializable
    private class AppleResponse(val results: List<AppleResult> = emptyList())

    @Serializable
    private class AppleResult(
        val collectionName: String? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val feedUrl: String? = null,
        val artworkUrl600: String? = null,
        val artworkUrl100: String? = null,
    )

    companion object {
        /** Apple's answers are a few hundred kilobytes; anything far larger isn't one. */
        val LIMIT = BodyLimit(2L * 1024 * 1024, 30.seconds)
        const val MAX_RESULTS = 50
        const val MAX_TEXT = 300

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        fun url(term: String): HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("itunes.apple.com")
            .addPathSegment("search")
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("term", term)
            .build()

        fun lookupUrl(id: Long): HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("itunes.apple.com")
            .addPathSegment("lookup")
            .addQueryParameter("id", id.toString())
            .addQueryParameter("entity", "podcast")
            .build()

        /** Control and formatting characters (bidi overrides included) removed, whitespace collapsed, capped. */
        fun clean(text: String?): String? = text
            ?.filterNot { Character.getType(it).let { type -> type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() } }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_TEXT)
            ?.takeIf { it.isNotEmpty() }
    }
}
