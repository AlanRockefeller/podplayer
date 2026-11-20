/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.dikarya.podplayer.core.data.importing.ImportText
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.Feed

/** A link the owner may copy or share. [isShowSite]: an episode without its own page, so the show's website. */
data class ShareLink(val url: String, val isShowSite: Boolean)

/**
 * Links to copy or share (owner, 2026-11-18; SPEC §12): only the web pages a feed names, the episode's item link
 * or the show's website. Never the feed's address or the enclosure's (they can carry a private feed's token), so a
 * link on the same host and path as either is left out. Only http and https, without user names or passwords.
 */
object ShareLinks {
    /** The episode's own page, else the show's website, else null. */
    fun episode(episode: Episode, feed: Feed?): ShareLink? {
        val secrets = listOfNotNull(episode.enclosure.url, feed?.url)
        safe(episode.link, secrets)?.let { return ShareLink(it, isShowSite = false) }
        // The show's website is checked against the enclosure too (Codex review, PR #30).
        return safe(feed?.link, secrets)?.let { ShareLink(it, isShowSite = true) }
    }

    /** The show's website, or null. */
    fun show(feed: Feed): ShareLink? = safe(feed.link, listOf(feed.url))?.let { ShareLink(it, isShowSite = false) }

    /** What Share sends: "Title — Show" (whichever are known), then the link. */
    fun text(title: String?, show: String?, link: ShareLink): String {
        val heading = listOfNotNull(ImportText.clean(title), ImportText.clean(show)).joinToString(" — ")
        return if (heading.isEmpty()) link.url else "$heading\n${link.url}"
    }

    private fun safe(raw: String?, secrets: List<String>): String? {
        val url = raw?.trim()?.toHttpUrlOrNull() ?: return null
        if (secrets.any { secret -> secret.toHttpUrlOrNull()?.let { sameResource(url, it) } == true }) return null
        return url.newBuilder().username("").password("").build().toString()
    }

    private fun sameResource(a: HttpUrl, b: HttpUrl) = a.host == b.host && a.encodedPath == b.encodedPath
}
