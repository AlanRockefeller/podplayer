/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import okhttp3.HttpUrl.Companion.toHttpUrl
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.RedirectChain
import us.dikarya.podplayer.core.net.Validators

/** A 200 answer with no redirects, as the gate returns it. */
internal fun success(body: ByteArray, contentType: String?, url: String = "https://example.com/") =
    FetchResult.Success(body, contentType, Validators.None, RedirectChain(url.toHttpUrl(), emptyList()))

internal fun rss(title: String, episodes: Int = 3, author: String = "A Host") = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>""")
    append("<title>$title</title><itunes:author>$author</itunes:author><description>About $title</description>")
    for (n in episodes downTo 1) {
        append("<item><guid>g$n</guid><title>Episode $n</title><pubDate>Mon, 0$n Jun 2026 10:00:00 GMT</pubDate>")
        append("""<enclosure url="https://cdn.example.com/$n.mp3" type="audio/mpeg" length="1000"/></item>""")
    }
    append("</channel></rss>")
}
