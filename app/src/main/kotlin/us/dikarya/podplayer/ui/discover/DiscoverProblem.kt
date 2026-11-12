/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.content.res.Resources
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.feed.ParseProblem
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.UrlProblem

/** Why a search, preview or OPML import didn't work, for a plain message (CLAUDE.md: bad input never crashes). */
sealed interface DiscoverProblem {
    data class Net(val error: NetError) : DiscoverProblem

    /** Search needs the network and there is none. Nothing was sent. */
    data object Offline : DiscoverProblem

    data object BadSearchResponse : DiscoverProblem

    data class BadUrl(val problem: UrlProblem) : DiscoverProblem

    data class NotAFeed(val problem: ParseProblem) : DiscoverProblem

    data object FileTooLarge : DiscoverProblem

    data object FileUnreadable : DiscoverProblem

    data class NotOpml(val problem: ParseProblem) : DiscoverProblem

    /** The library database isn't open (DataLayer reported it damaged or failed). */
    data object LibraryClosed : DiscoverProblem
}

/** The message shown for [problem]. No URL is ever quoted (feed URLs are secrets). */
fun Resources.message(problem: DiscoverProblem): String = when (problem) {
    DiscoverProblem.Offline -> getString(R.string.problem_offline)
    DiscoverProblem.BadSearchResponse -> getString(R.string.problem_search_answer)
    DiscoverProblem.FileTooLarge -> getString(R.string.problem_file_too_large)
    DiscoverProblem.FileUnreadable -> getString(R.string.problem_file_unreadable)
    DiscoverProblem.LibraryClosed -> getString(R.string.problem_library_closed)
    is DiscoverProblem.NotOpml -> getString(R.string.problem_not_opml)
    is DiscoverProblem.NotAFeed -> when (problem.problem) {
        is ParseProblem.NotAFeed, ParseProblem.HtmlPage -> getString(R.string.problem_not_a_feed_page)
        else -> getString(R.string.problem_not_a_feed)
    }
    is DiscoverProblem.BadUrl -> when (problem.problem) {
        UrlProblem.DisallowedScheme -> getString(R.string.problem_url_scheme)
        UrlProblem.TooLong -> getString(R.string.problem_url_too_long)
        UrlProblem.Malformed, UrlProblem.AmbiguousNumericHost -> getString(R.string.problem_url_malformed)
    }
    is DiscoverProblem.Net -> when (val error = problem.error) {
        is NetError.BlockedAddress -> getString(R.string.problem_net_blocked)
        is NetError.HttpStatus -> when (error.code) {
            401, 403 -> getString(R.string.problem_net_forbidden, error.code)
            404, 410 -> getString(R.string.problem_net_not_found, error.code)
            else -> getString(R.string.problem_net_status, error.code)
        }
        is NetError.TooLarge -> getString(R.string.problem_net_too_large)
        NetError.TimedOut, NetError.Stalled -> getString(R.string.problem_net_timeout)
        is NetError.TooManyRedirects, is NetError.BadRedirect -> getString(R.string.problem_net_redirects)
        is NetError.InvalidUrl -> getString(R.string.problem_url_malformed)
        is NetError.Network -> getString(R.string.problem_net_unreachable)
        is NetError.Storage -> getString(R.string.problem_net_unreachable)
    }
}
