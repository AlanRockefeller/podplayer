/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import org.junit.Test

/** CLAUDE.md: debug builds crash on a violated invariant, so problems get noticed. */
class DebugInvariantHandlerTest {
    @Test(expected = IllegalStateException::class)
    fun aViolatedInvariantCrashesADebugBuild() {
        BuildTypeSetup.invariantHandler { null }.onViolation("queue position out of range")
    }
}
