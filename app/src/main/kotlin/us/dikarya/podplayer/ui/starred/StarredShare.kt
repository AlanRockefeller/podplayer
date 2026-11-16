/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.starred

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.dikarya.podplayer.core.data.importing.ImportText
import us.dikarya.podplayer.core.model.Episode

/**
 * The plain-text list "Share starred" sends (SPEC §12): each episode's title and show, and its item link from the
 * feed. Never the enclosure URL, which can carry a private feed's token: an item link equal to the enclosure is
 * left out too. Only http and https links, without user names or passwords.
 */
object StarredShare {
    fun text(heading: String, untitled: String, episodes: List<Pair<Episode, String?>>): String = buildString {
        append(heading).append('\n')
        for ((episode, show) in episodes) {
            append('\n')
            append(ImportText.clean(episode.title) ?: untitled)
            ImportText.clean(show)?.let { append(" — ").append(it) }
            append('\n')
            link(episode)?.let { append(it).append('\n') }
        }
    }

    private fun link(episode: Episode): String? {
        val url = episode.link?.trim()?.toHttpUrlOrNull() ?: return null
        val enclosure = episode.enclosure.url.toHttpUrlOrNull()
        if (enclosure != null && url.host == enclosure.host && url.encodedPath == enclosure.encodedPath) return null
        return url.newBuilder().username("").password("").build().toString()
    }
}
