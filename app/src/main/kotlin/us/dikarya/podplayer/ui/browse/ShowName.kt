/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.net.URI
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.model.Feed

/**
 * A show's name: its title, else its feed's host (a show that hasn't loaded yet). Only the host, since the rest of a
 * private feed's address can carry its token. Null when neither exists.
 */
fun showName(title: String?, feedUrl: String): String? =
    title?.takeIf { it.isNotBlank() } ?: try {
        URI(feedUrl).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

/** [showName] for display, with "Untitled show" only when the address has no host either. */
@Composable
fun showNameOrUntitled(feed: Feed): String = showName(feed.title, feed.url) ?: stringResource(R.string.untitled_show)
