/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.model

/** What happens when an invariant does not hold. `:app` installs one at startup. */
fun interface InvariantHandler {
    fun onViolation(message: String)
}

/** Debug builds and tests: crash, so the problem gets noticed. */
object StrictInvariantHandler : InvariantHandler {
    override fun onViolation(message: String): Unit = throw IllegalStateException(describe(message))
}

/** Release builds: record the violation and keep going. */
class LoggingInvariantHandler(private val log: (String) -> Unit) : InvariantHandler {
    override fun onViolation(message: String) = log(describe(message))
}

private fun describe(message: String) = "Invariant violated: $message"

object Invariants {
    /** Strict until the app says otherwise, so a test or a misconfigured build fails loudly. */
    @Volatile
    var handler: InvariantHandler = StrictInvariantHandler
}

/**
 * Checks something that must always be true. Crashes in debug builds and logs in release builds.
 * The message must not contain unredacted URLs or other secrets.
 */
inline fun assertInvariant(condition: Boolean, message: () -> String) {
    if (!condition) Invariants.handler.onViolation(message())
}
