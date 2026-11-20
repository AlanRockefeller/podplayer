/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.importing

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.redact

/** One episode's marks from another app. Matched to the library by show, then title and date ([EpisodeMatcher]). */
data class ImportEpisode(
    val title: String?,
    /** Epoch ms, or null when the file had no readable date. */
    val publishedAt: Long? = null,
    val starred: Boolean = false,
    val starredAt: Long? = null,
    val played: Boolean = false,
    val positionMs: Long? = null,
)

/** One show: a name to search for, or a feed address (already through the URL policy), or both. */
data class ImportShow(val title: String?, val feedUrl: String?, val episodes: List<ImportEpisode>) {
    override fun toString() = "ImportShow(title=$title, feedUrl=${feedUrl?.let(::redact)}, episodes=${episodes.size})"
}

enum class ImportFileProblem { TooLarge, NotAnImportFile, NewerVersion, TooManyItems }

sealed interface ImportFileResult {
    /** [dropped]: shows left out because they had neither a usable name nor a usable address. */
    data class Ok(val shows: List<ImportShow>, val dropped: Int) : ImportFileResult

    data class Refused(val problem: ImportFileProblem) : ImportFileResult
}

/**
 * The import file (SPEC §12, written by the M1-S9c adb export or by hand). JSON, versioned, size-limited, and
 * hostile like any feed: every string is cleaned and capped, feed addresses go through the URL policy, numbers
 * out of range are dropped. A breach of a limit refuses the whole file (CLAUDE.md: reject and say why).
 *
 * ```
 * {"format":"podplayer-import","version":1,"source":"…","shows":[
 *   {"title":"…","feedUrl":"https://…","episodes":[
 *     {"title":"…","published":"2026-03-04" | "2026-03-04T08:30:00Z","starred":true,"starredAt":<epoch ms>,
 *      "played":true,"positionMs":754000}]}]}
 * ```
 */
object ImportFile {
    const val FORMAT = "podplayer-import"
    const val VERSION = 1
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_SHOWS = 500
    const val MAX_EPISODES = 20_000
    const val MAX_TEXT = 300

    /** A week: longer than any episode, so anything beyond is nonsense. */
    private const val MAX_POSITION_MS = 7L * 24 * 3600 * 1000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** A cheap check before trying: does this text start like an import file? */
    fun looksLikeOne(text: String): Boolean {
        val start = text.trimStart()
        return start.startsWith("{") && start.take(200).contains("\"$FORMAT\"")
    }

    fun parse(bytes: ByteArray): ImportFileResult {
        if (bytes.size > MAX_BYTES) return ImportFileResult.Refused(ImportFileProblem.TooLarge)
        val file = try {
            json.decodeFromString(FileDto.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            return ImportFileResult.Refused(ImportFileProblem.NotAnImportFile)
        } catch (_: IllegalArgumentException) {
            return ImportFileResult.Refused(ImportFileProblem.NotAnImportFile)
        } catch (_: StackOverflowError) {
            return ImportFileResult.Refused(ImportFileProblem.NotAnImportFile)
        }
        if (file.format != FORMAT || file.version == null || file.version < 1) return ImportFileResult.Refused(ImportFileProblem.NotAnImportFile)
        if (file.version > VERSION) return ImportFileResult.Refused(ImportFileProblem.NewerVersion)
        if (file.shows.size > MAX_SHOWS || file.shows.sumOf { it.episodes.size } > MAX_EPISODES) {
            return ImportFileResult.Refused(ImportFileProblem.TooManyItems)
        }
        val shows = file.shows.mapNotNull { show ->
            val title = ImportText.clean(show.title)
            val feedUrl = show.feedUrl?.let { (UrlPolicy.checkFeedUrl(it) as? UrlCheck.Allowed)?.url?.toString() }
            if (title == null && feedUrl == null) return@mapNotNull null
            ImportShow(title, feedUrl, show.episodes.mapNotNull(::episode))
        }
        return ImportFileResult.Ok(shows, dropped = file.shows.size - shows.size)
    }

    private fun episode(dto: EpisodeDto): ImportEpisode? {
        val title = ImportText.clean(dto.title) ?: return null
        return ImportEpisode(
            title = title,
            publishedAt = dto.published?.let(::date),
            starred = dto.starred,
            starredAt = dto.starredAt?.takeIf { it > 0 },
            played = dto.played,
            positionMs = dto.positionMs?.takeIf { it in 1..MAX_POSITION_MS },
        )
    }

    /** `2026-03-04` (midnight UTC) or an ISO date and time with an offset; anything else is no date. */
    private fun date(text: String): Long? {
        val value = text.trim().take(40)
        return try {
            if (value.length == 10) {
                LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } else {
                runCatching { Instant.parse(value) }.getOrNull()?.toEpochMilli() ?: OffsetDateTime.parse(value).toInstant().toEpochMilli()
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    @Serializable
    private class FileDto(val format: String? = null, val version: Int? = null, val shows: List<ShowDto> = emptyList())

    @Serializable
    private class ShowDto(val title: String? = null, val feedUrl: String? = null, val episodes: List<EpisodeDto> = emptyList())

    @Serializable
    private class EpisodeDto(
        val title: String? = null,
        val published: String? = null,
        val starred: Boolean = false,
        val starredAt: Long? = null,
        val played: Boolean = false,
        val positionMs: Long? = null,
    )
}

/** Text from another app or a pasted list: control and formatting characters removed, spaces collapsed, capped. */
object ImportText {
    private val spaces = Regex("\\s+")

    fun clean(text: String?, max: Int = ImportFile.MAX_TEXT): String? = text
        ?.filterNot { Character.getType(it).let { type -> type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() } }
        ?.replace(spaces, " ")
        ?.trim()
        ?.take(max)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
