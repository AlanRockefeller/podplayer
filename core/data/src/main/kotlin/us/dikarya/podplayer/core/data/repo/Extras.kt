/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.ParsedFeed

/*
 * Feed fields kept for later milestones (SPEC §4), stored as JSON columns. Reading never throws:
 * a damaged value (for example from a hand-edited backup) reads as empty.
 */

internal val columnJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class StoredFunding(val url: String, val text: String? = null)

@Serializable
data class StoredPerson(
    val name: String,
    val role: String? = null,
    val group: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
)

@Serializable
data class StoredLicense(val identifier: String? = null, val url: String? = null)

@Serializable
data class FeedExtras(
    val funding: List<StoredFunding> = emptyList(),
    val persons: List<StoredPerson> = emptyList(),
    val license: StoredLicense? = null,
) {
    companion object {
        fun of(feed: ParsedFeed) = FeedExtras(
            funding = feed.funding.map { StoredFunding(it.url, it.text) },
            persons = feed.persons.map { StoredPerson(it.name, it.role, it.group, it.imageUrl, it.link) },
            license = feed.license?.let { StoredLicense(it.identifier, it.url) },
        )

        fun encode(extras: FeedExtras): String? =
            if (extras == FeedExtras()) null else columnJson.encodeToString(serializer(), extras)

        fun decode(json: String?): FeedExtras =
            json?.let { runCatching { columnJson.decodeFromString(serializer(), it) }.getOrNull() } ?: FeedExtras()
    }
}

@Serializable
data class StoredTranscript(val url: String, val type: String? = null, val language: String? = null, val rel: String? = null)

@Serializable
data class StoredSoundbite(val startMs: Long, val durationMs: Long, val title: String? = null)

@Serializable
data class StoredAlternateEnclosure(
    val type: String? = null,
    val length: Long? = null,
    val bitrate: Long? = null,
    val title: String? = null,
    val language: String? = null,
    val isDefault: Boolean = false,
    val sources: List<String> = emptyList(),
)

@Serializable
data class EpisodeExtras(
    val transcripts: List<StoredTranscript> = emptyList(),
    val soundbites: List<StoredSoundbite> = emptyList(),
    val alternateEnclosures: List<StoredAlternateEnclosure> = emptyList(),
) {
    companion object {
        fun of(episode: ParsedEpisode) = EpisodeExtras(
            transcripts = episode.transcripts.map { StoredTranscript(it.url, it.type, it.language, it.rel) },
            soundbites = episode.soundbites.map { StoredSoundbite(it.startMs, it.durationMs, it.title) },
            alternateEnclosures = episode.alternateEnclosures.map {
                StoredAlternateEnclosure(it.type, it.length, it.bitrate, it.title, it.language, it.isDefault, it.sources)
            },
        )

        fun encode(extras: EpisodeExtras): String? =
            if (extras == EpisodeExtras()) null else columnJson.encodeToString(serializer(), extras)

        fun decode(json: String?): EpisodeExtras =
            json?.let { runCatching { columnJson.decodeFromString(serializer(), it) }.getOrNull() } ?: EpisodeExtras()
    }
}

private val stringList = ListSerializer(String.serializer())

internal fun encodeStrings(values: List<String>): String? =
    if (values.isEmpty()) null else columnJson.encodeToString(stringList, values)

internal fun decodeStrings(json: String?): List<String> =
    json?.let { runCatching { columnJson.decodeFromString(stringList, it) }.getOrNull() } ?: emptyList()
