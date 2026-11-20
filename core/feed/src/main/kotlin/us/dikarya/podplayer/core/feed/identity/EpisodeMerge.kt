/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed.identity

import java.security.MessageDigest
import java.text.Normalizer
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.net.redact

/** What the merge needs to know about an episode already in the library (S4 maps its rows to this). */
data class StoredEpisode(
    val id: Long,
    val guid: String?,
    val enclosureUrl: String?,
    val title: String?,
    /** The feed's date, or the first-seen time when the feed had none. */
    val publishedAt: Long?,
    /** [EpisodeMerge.fingerprint] of the item last merged into this row. */
    val fingerprint: String?,
    val inFeed: Boolean,
) {
    override fun toString() =
        "StoredEpisode($id, $guid, ${enclosureUrl?.let(::redact)}, $title, $publishedAt, inFeed=$inFeed)"
}

enum class MatchedBy {
    Guid,
    EnclosureUrl,
    TitleAndDay,

    /** An undated item, matched by a title only one stored episode has. */
    Title,
}

data class NewEpisode(
    val episode: ParsedEpisode,
    /** The item's date, or the first-seen time (SPEC §4: unparseable dates use the first-seen time). */
    val publishedAt: Long,
    val fingerprint: String,
)

/**
 * New metadata for a stored episode. Nothing here touches played state, position or downloads: a re-post
 * or a host move only changes what the feed says (SPEC §4). Apply `publishedAt` only when it is non-null.
 */
data class EpisodeUpdate(
    val id: Long,
    val episode: ParsedEpisode,
    val fingerprint: String,
    val matchedBy: MatchedBy,
    /** Same episode, newer date: the publisher re-posted it. */
    val reposted: Boolean,
)

/** The changes one refresh makes, for S4 to apply in one transaction. */
data class FeedChanges(
    val inserts: List<NewEpisode> = emptyList(),
    val updates: List<EpisodeUpdate> = emptyList(),
    /** Stored episodes the feed no longer lists. They stay in the library, marked (SPEC §4). */
    val noLongerInFeed: List<Long> = emptyList(),
    /** Episodes marked "no longer in feed" that are back. */
    val backInFeed: List<Long> = emptyList(),
    /** Matched episodes whose metadata didn't change. */
    val unchanged: Int = 0,
    /** Items without a usable enclosure: not episodes. */
    val skippedWithoutEnclosure: Int = 0,
    /** Items listed twice with the same identity; only the first counts. */
    val duplicatesDropped: Int = 0,
)

/**
 * Episode identity and merge (SPEC §4, LESSONS "Episode identity") as a pure function.
 *
 * Identity within a feed:
 * 1. GUID, when it is unique within this copy of the feed and names one stored episode (or, when several
 *    stored episodes share it, the one with the item's enclosure URL).
 * 2. Otherwise, or when the GUID is new (publishers change GUIDs when they move hosts): the enclosure URL,
 *    when exactly one unresolved item and one unmatched stored episode have it.
 * 3. Then the normalized title plus the publication day (a day either way, for hosts that shift times
 *    across midnight). Undated items: a title that exactly one unmatched stored episode has.
 *
 * A fallback never takes a stored episode whose own GUID is still in the feed, so a new item with a reused
 * title can't steal another episode's history. Duplicate GUIDs are kept apart by the fallbacks; an item
 * whose GUID, enclosure, title and day all repeat an earlier one is a duplicate listing and is dropped.
 */
object EpisodeMerge {
    private const val DAY_MS = 24L * 3600 * 1000

    fun merge(stored: List<StoredEpisode>, parsed: List<ParsedEpisode>, now: Long): FeedChanges {
        var skipped = 0
        var duplicates = 0
        val items = ArrayList<ParsedEpisode>(parsed.size)
        val seen = HashSet<List<Any?>>()
        val guidCounts = parsed.mapNotNull { it.guid }.groupingBy { it }.eachCount()
        for (item in parsed) {
            if (item.enclosure == null) {
                skipped++
                continue
            }
            val unique = item.guid != null && guidCounts[item.guid] == 1
            val key: List<Any?> = if (unique) listOf(item.guid) else listOf(item.guid, item.enclosure.url, normalizeTitle(item.title), day(item.publishedAt))
            if (!seen.add(key)) {
                duplicates++
                continue
            }
            items += item
        }

        val guidsInFeed = items.mapNotNullTo(HashSet()) { it.guid }
        val matched = HashMap<Int, Pair<StoredEpisode, MatchedBy>>()
        val taken = HashSet<Long>()

        // 1. Unique GUIDs.
        val storedByGuid = stored.filter { it.guid != null }.groupBy { it.guid }
        for ((index, item) in items.withIndex()) {
            if (item.guid == null || guidCounts[item.guid] != 1) continue
            val candidates = storedByGuid[item.guid].orEmpty().filter { it.id !in taken }
            // Several stored episodes with this GUID (it was reused before): only a matching enclosure tells
            // which one this is; otherwise the fallbacks below decide.
            val pick = if (candidates.size == 1) {
                candidates.single()
            } else {
                candidates.singleOrNull { it.enclosureUrl == item.enclosure?.url } ?: continue
            }
            matched[index] = pick to MatchedBy.Guid
            taken += pick.id
        }

        // A stored episode may be claimed by a fallback only if its own GUID isn't in this copy of the feed,
        // or if the claiming item shares that (duplicated) GUID.
        fun claimable(row: StoredEpisode, item: ParsedEpisode) =
            row.id !in taken && (row.guid == null || row.guid !in guidsInFeed || row.guid == item.guid)

        // 2. Enclosure URL, only when it is unambiguous on both sides.
        val itemsByUrl = items.indices.filter { it !in matched }.groupBy { items[it].enclosure!!.url }
        val storedByUrl = stored.filter { it.enclosureUrl != null }.groupBy { it.enclosureUrl }
        for ((url, indices) in itemsByUrl) {
            if (indices.size != 1) continue
            val index = indices.single()
            val candidates = storedByUrl[url].orEmpty().filter { claimable(it, items[index]) }
            if (candidates.size != 1) continue
            matched[index] = candidates.single() to MatchedBy.EnclosureUrl
            taken += candidates.single().id
        }

        // 3. Normalized title plus day; undated items by a unique title.
        val storedByTitle = stored.groupBy { normalizeTitle(it.title) }
        for ((index, item) in items.withIndex()) {
            if (index in matched) continue
            val title = normalizeTitle(item.title) ?: continue
            val candidates = storedByTitle[title].orEmpty().filter { claimable(it, item) }
            val itemDay = day(item.publishedAt)
            if (itemDay != null) {
                val pick = candidates
                    .filter { row -> day(row.publishedAt)?.let { kotlin.math.abs(it - itemDay) <= 1 } == true }
                    .minByOrNull { kotlin.math.abs((it.publishedAt ?: 0) - item.publishedAt!!) }
                    ?: continue
                matched[index] = pick to MatchedBy.TitleAndDay
                taken += pick.id
            } else if (candidates.size == 1) {
                matched[index] = candidates.single() to MatchedBy.Title
                taken += candidates.single().id
            }
        }

        val inserts = ArrayList<NewEpisode>()
        val updates = ArrayList<EpisodeUpdate>()
        val back = ArrayList<Long>()
        var unchanged = 0
        for ((index, item) in items.withIndex()) {
            val fingerprint = fingerprint(item)
            val match = matched[index]
            if (match == null) {
                inserts += NewEpisode(item, item.publishedAt ?: now, fingerprint)
                continue
            }
            val (row, by) = match
            if (!row.inFeed) back += row.id
            if (row.fingerprint == fingerprint) {
                unchanged++
            } else {
                val reposted = by == MatchedBy.Guid && item.publishedAt != null && row.publishedAt != null &&
                    item.publishedAt > row.publishedAt
                updates += EpisodeUpdate(row.id, item, fingerprint, by, reposted)
            }
        }
        val gone = stored.filter { it.inFeed && it.id !in taken }.map { it.id }
        return FeedChanges(inserts, updates, gone, back, unchanged, skipped, duplicates)
    }

    /**
     * A stable digest of everything the parser keeps for an item, so unchanged items cost no write.
     * Each field is length-prefixed, so text moving between fields changes the digest.
     */
    fun fingerprint(episode: ParsedEpisode): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: Any?) {
            val text = value?.toString() ?: "\u0000"
            val bytes = text.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray())
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        with(episode) {
            field(VERSION)
            field(guid)
            field(title)
            field(publishedAt)
            field(enclosure?.url)
            field(enclosure?.type)
            field(enclosure?.length)
            field(durationMs)
            field(notes)
            field(imageUrl)
            field(link)
            field(season)
            field(episode.episode)
            field(chapters?.url)
            field(chapters?.type)
            field(transcripts.size)
            transcripts.forEach { field(it.url); field(it.type); field(it.language); field(it.rel) }
            field(soundbites.size)
            soundbites.forEach { field(it.startMs); field(it.durationMs); field(it.title) }
            field(alternateEnclosures.size)
            alternateEnclosures.forEach { alt ->
                field(alt.type); field(alt.length); field(alt.bitrate); field(alt.title); field(alt.language)
                field(alt.isDefault); field(alt.sources.size); alt.sources.forEach { field(it) }
            }
        }
        val hex = "0123456789abcdef"
        return buildString {
            for (byte in digest.digest()) {
                val v = byte.toInt() and 0xFF
                append(hex[v ushr 4]).append(hex[v and 0x0F])
            }
        }
    }

    /** Bumped when the fingerprint's fields change, so every row gets rewritten once. */
    private const val VERSION = 1

    private val whitespace = Regex("\\s+")

    /** Case, compatibility forms (full-width letters) and runs of whitespace don't distinguish titles. */
    internal fun normalizeTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        return Normalizer.normalize(title, Normalizer.Form.NFKC)
            .lowercase()
            .replace(whitespace, " ")
            .trim()
            .ifEmpty { null }
    }

    private fun day(millis: Long?): Long? = millis?.let { Math.floorDiv(it, DAY_MS) }
}
