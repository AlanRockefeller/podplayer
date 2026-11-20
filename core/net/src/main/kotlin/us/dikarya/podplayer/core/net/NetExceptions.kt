/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.net

import java.io.IOException
import java.net.UnknownHostException

// OkHttp's Dns and interceptors can only fail with IOException, so the gate's refusals are IOExceptions.
// NetGate turns them into NetError values; ExoPlayer sees them as ordinary I/O failures.
// Messages never contain URLs: only hosts, which redaction keeps anyway.

/** The address filter refused [host]. A lookup failure, so callers that expect DNS errors handle it. */
class BlockedAddressException(val host: String, val reason: BlockReason?) :
    UnknownHostException("refused to connect to $host: ${reason ?: "no allowed address"}")

/** A URL the policy refuses, found on a request or a redirect hop. */
class DisallowedUrlException(val problem: UrlProblem) : IOException("refused URL: $problem")

class TooManyRedirectsException(val limit: Int) : IOException("more than $limit redirects")

class BadRedirectException(reason: String) : IOException(reason)

class BodyTooLargeException(val limitBytes: Long) : IOException("body larger than $limitBytes bytes")
