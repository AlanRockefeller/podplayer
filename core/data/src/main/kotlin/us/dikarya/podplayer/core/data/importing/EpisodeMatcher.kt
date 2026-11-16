/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.importing

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.model.Episode

/**
 * Finds a show's library episode for each imported one (SPEC §12: by show, then episode title and date). The
 * caller has already picked the show. Titles must match after [normalize]; one match is taken whatever its date
 * (re-posts move dates), several are settled by the nearest date within [DATE_WINDOW_MS], and otherwise left
 * unmatched rather than guessed. Each library episode is used at most once.
 */
object EpisodeMatcher {
    const val DATE_WINDOW_MS = 2L * 24 * 3600 * 1000

    private val notWord = Regex("[^\\p{L}\\p{N}]+")

    /** Case, accents' composed forms, punctuation and spacing don't matter. */
    fun normalize(title: String?): String =
        Normalizer.normalize(title.orEmpty(), Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(notWord, " ").trim()

    fun matchAll(entries: List<ImportEpisode>, library: List<Episode>): List<Episode?> {
        val byTitle = library.groupBy { normalize(it.title) }
        val used = HashSet<Long>()
        return entries.map { entry ->
            val key = normalize(entry.title)
            if (key.isEmpty()) return@map null
            val candidates = byTitle[key].orEmpty().filter { it.id !in used }
            val all = byTitle[key].orEmpty()
            val match = when {
                candidates.isEmpty() -> null
                all.size == 1 -> candidates.single()
                entry.publishedAt == null -> null
                else -> candidates
                    .filter { abs(it.publishedAt - entry.publishedAt) <= DATE_WINDOW_MS }
                    .minByOrNull { abs(it.publishedAt - entry.publishedAt) }
            }
            match?.also { used += it.id }
        }
    }
}

/** What [ImportApplier.apply] did; [unmatched] holds the titles the owner should see. */
data class ImportApplyReport(val matched: Int, val unmatched: List<String>)

/**
 * Applies an import's marks to a subscribed show: stars (with the file's own time when it has one), played marks,
 * and positions, but a position only where the library has no progress of its own. Applying again changes nothing.
 */
object ImportApplier {
    suspend fun apply(repos: Repositories, feedId: Long, entries: List<ImportEpisode>): ImportApplyReport {
        val library = repos.episodes.forFeed(feedId).first()
        val matches = EpisodeMatcher.matchAll(entries, library)
        val unmatched = ArrayList<String>()
        var matched = 0
        entries.zip(matches).forEach { (entry, episode) ->
            if (episode == null) {
                entry.title?.let(unmatched::add)
                return@forEach
            }
            matched++
            if (entry.starred) repos.starred.starIfNot(episode.id, entry.starredAt)
            val record = repos.playback.get(episode.id)
            if (entry.played) {
                if (record?.played != true) repos.playback.setPlayed(episode.id, true)
            } else {
                val position = entry.positionMs
                if (position != null && (record == null || (!record.played && record.positionMs == 0L))) {
                    repos.playback.savePosition(episode.id, position, null)
                }
            }
        }
        return ImportApplyReport(matched, unmatched)
    }
}
