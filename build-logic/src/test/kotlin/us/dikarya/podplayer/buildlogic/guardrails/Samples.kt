/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.buildlogic.guardrails

internal object Samples {
    fun text(name: String): String =
        requireNotNull(Samples::class.java.getResource("/samples/$name.sample")) { "missing sample $name" }
            .readText()
}
