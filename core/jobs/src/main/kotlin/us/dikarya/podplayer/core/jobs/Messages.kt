/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import java.util.Locale
import us.dikarya.podplayer.core.feed.FeedWarning
import us.dikarya.podplayer.core.feed.ParseProblem
import us.dikarya.podplayer.core.jobs.download.ProbeProblem
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.download.RestartReason
import us.dikarya.podplayer.core.net.scrubUrls

/**
 * Plain-language reasons for the refresh log (SPEC §8) and download log (SPEC §9). They are stored with the
 * log rows, so they are log text in English, like the app log; the repositories redact and cap them again.
 * Nothing here quotes a full URL.
 */
internal object Messages {
    fun net(error: NetError, what: String = "the server"): String = scrubUrls(
        when (error) {
            is NetError.BlockedAddress ->
                "${error.host} is on a private network address. Only a feed whose address you typed may use one."
            is NetError.InvalidUrl -> "The address isn't one this app can use (${error.problem.name})."
            is NetError.TooManyRedirects -> "The address redirects more than ${error.limit} times."
            is NetError.BadRedirect -> "The address redirects somewhere this app can't follow: ${error.reason}."
            is NetError.HttpStatus -> http(error.code, what)
            is NetError.TooLarge -> "It is larger than the ${mib(error.limitBytes)} limit."
            NetError.TimedOut -> "${what.replaceFirstChar { it.uppercase() }} took too long to answer."
            NetError.Stalled -> "The download stopped receiving data."
            is NetError.Network -> "Couldn't reach ${what} (${error.detail})."
            is NetError.Storage -> "Couldn't write to storage (${error.detail})."
        },
    )

    private fun http(code: Int, what: String): String = when (code) {
        401, 403 -> "$what refused access (HTTP $code). A private feed may need a new address.".capitalized()
        404, 410 -> "$what says there is nothing at this address (HTTP $code).".capitalized()
        429 -> "$what asked us to slow down (HTTP 429).".capitalized()
        in 500..599 -> "$what had a problem (HTTP $code).".capitalized()
        else -> "$what answered HTTP $code.".capitalized()
    }

    fun parse(problem: ParseProblem): String = "The feed couldn't be read: ${problem.logReason}."

    fun warnings(warnings: List<FeedWarning>): List<String> = warnings.map { warning ->
        when (warning) {
            is FeedWarning.Repaired -> "the feed had mistakes that were repaired"
            is FeedWarning.DuplicateGuids ->
                "${warning.guids} episode IDs are used more than once (a broken feed; validators: ${warning.validatorLinks.joinToString(" ")})"
            is FeedWarning.UrlsRejected -> "${warning.count} addresses in the feed were refused"
            is FeedWarning.FieldsTruncated -> "${warning.count} fields were too long and were cut"
        }
    }

    fun probe(problem: ProbeProblem): String = when (problem) {
        ProbeProblem.Unreadable -> "The downloaded file couldn't be read."
        ProbeProblem.Unrecognized -> "The file isn't an audio format this app can play."
        ProbeProblem.Video -> "The file is a video. This version plays audio only."
        ProbeProblem.NotAudio -> "The file isn't audio."
        ProbeProblem.NoDuration -> "The audio's length couldn't be read, so the file may be damaged."
        ProbeProblem.TooSlow -> "Checking the file took too long."
    }

    fun restart(reason: RestartReason): String = when (reason) {
        RestartReason.NoValidator -> "Started over: the server gave no way to check the part already downloaded."
        RestartReason.RangeIgnored -> "Started over: the server ignored the request to resume."
        RestartReason.FileChanged -> "Started over: the file on the server changed."
        RestartReason.BadContentRange -> "Started over: the server sent the wrong part of the file."
        RestartReason.RangeNotSatisfiable -> "Started over: the server couldn't resume from where it stopped."
    }

    fun storageCap(capBytes: Long): String =
        "Downloads would go over the storage limit of ${mib(capBytes)}. Delete some, or raise the limit."

    fun mib(bytes: Long): String = when {
        bytes >= GIB -> String.format(Locale.ROOT, "%.1f GiB", bytes / GIB)
        else -> String.format(Locale.ROOT, "%.1f MiB", bytes / MIB)
    }

    private fun String.capitalized() = replaceFirstChar { it.uppercase() }

    private const val MIB = 1024.0 * 1024
    private const val GIB = MIB * 1024
}
