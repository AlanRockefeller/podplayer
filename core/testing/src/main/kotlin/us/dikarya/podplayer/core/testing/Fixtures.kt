/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.testing

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** Loads the fixture files under `src/main/resources` (see `corpus/README.txt`). */
object Fixtures {
    fun bytes(path: String): ByteArray {
        val stream = Fixtures::class.java.getResourceAsStream("/$path")
            ?: throw IllegalArgumentException("No fixture at $path")
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)

    /** A fresh JVM pull parser (kxml2), standing in for the platform parser Android provides. */
    fun xmlPullParser(): XmlPullParser = KXmlParser()
}
