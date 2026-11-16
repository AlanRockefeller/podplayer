/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import us.dikarya.podplayer.core.data.db.EpisodeActionEntity
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.EpisodeActionKind

/** What a star or unstar changed, so Undo can put it back exactly (the old time keeps the list order). */
data class StarChange(val episodeId: Long, val starred: Boolean, val previousStarredAt: Long?)

/**
 * Starred episodes (SPEC §7, §12). Every change appends a Starred or Unstarred action for M2 sync in the same
 * transaction. Starring never touches the playback record: a played episode stays played.
 */
class StarredRepository internal constructor(
    private val db: PodDatabase,
    private val clock: () -> Long,
    private val deviceId: suspend () -> String,
) {
    /** Newest starred first; [query] matches the episode's or the show's title (blank: all). */
    fun starred(query: String = "", limit: Int = MAX_LIST): Flow<List<Episode>> =
        db.episodes().observeStarred(likePattern(query), limit).map { rows -> rows.map { it.toModel() } }

    /** The ids of [feedId]'s starred episodes (the show page's filter). */
    fun idsForFeed(feedId: Long): Flow<Set<Long>> = db.episodes().observeStarredIds(feedId).map { it.toSet() }

    /** Stars or unstars. Null when nothing changed: an unknown episode, or it already was so. */
    suspend fun setStarred(episodeId: Long, starred: Boolean): StarChange? = db.withTransaction {
        val old = db.episodes().get(episodeId) ?: return@withTransaction null
        if (old.starred == starred) return@withTransaction null
        write(episodeId, if (starred) clock() else null)
        StarChange(episodeId, starred, old.starredAt.takeIf { old.starred })
    }

    /** Reverses [change]: an unstar gets its old star time back. */
    suspend fun undo(change: StarChange) {
        db.withTransaction {
            val old = db.episodes().get(change.episodeId) ?: return@withTransaction
            val restored = if (change.starred) null else change.previousStarredAt ?: clock()
            if (old.starred == (restored != null) && old.starredAt == restored) return@withTransaction
            write(change.episodeId, restored)
        }
    }

    /** Stars [episodeId] at [at] (an import's own time) unless it is starred already. */
    suspend fun starIfNot(episodeId: Long, at: Long?): Boolean = db.withTransaction {
        val old = db.episodes().get(episodeId) ?: return@withTransaction false
        if (old.starred) return@withTransaction false
        write(episodeId, at ?: clock())
        true
    }

    private suspend fun write(episodeId: Long, starredAt: Long?) {
        db.episodes().setStarred(episodeId, starredAt != null, starredAt)
        db.episodeActions().insert(
            EpisodeActionEntity(
                episodeId = episodeId,
                kind = if (starredAt != null) EpisodeActionKind.Starred else EpisodeActionKind.Unstarred,
                positionMs = null,
                durationMs = null,
                at = clock(),
                deviceId = deviceId(),
            ),
        )
    }

    companion object {
        /** More than anyone stars; the list screen shows this many at most. */
        const val MAX_LIST = 2_000
        const val MAX_QUERY = 200

        /** `%query%` with LIKE's wildcards and the escape character taken literally, or null for a blank query. */
        internal fun likePattern(query: String): String? {
            val text = query.trim().take(MAX_QUERY)
            if (text.isEmpty()) return null
            val escaped = buildString {
                for (c in text) {
                    if (c == '\\' || c == '%' || c == '_') append('\\')
                    append(c)
                }
            }
            return "%$escaped%"
        }
    }
}
