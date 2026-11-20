/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.playback

import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CLAUDE.md: never decode untrusted image bytes outside the image sandbox. An enclosure can be a PNG; Media3's
 * default renderers would decode its image track with the platform's bitmap decoder in the app process.
 */
@RunWith(AndroidJUnit4::class)
class PlayerFactoryTest {
    @Test
    fun theProductionPlayerHasNoImageRendererButKeepsItsAudioRenderers() {
        val player = PlayerFactory.create(appContext(), TestSources(FakePlaybackStore()))
        try {
            val types = (0 until player.rendererCount).map { player.getRendererType(it) }
            assertFalse("renderers: $types", C.TRACK_TYPE_IMAGE in types)
            assertTrue("renderers: $types", C.TRACK_TYPE_AUDIO in types)
        } finally {
            player.release()
        }
    }
}
