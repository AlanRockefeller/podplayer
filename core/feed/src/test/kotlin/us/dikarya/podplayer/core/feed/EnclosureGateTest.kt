/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.NetGate

/**
 * SPEC §14 "enclosure on a private address", end to end: the parser keeps the URL (it is a valid http URL),
 * and the network gate refuses it before any connection is made.
 */
class EnclosureGateTest {
    @Test
    fun `every private enclosure in the fixture is refused by the gate`() = runBlocking {
        val lookups = mutableListOf<String>()
        // Literal addresses never reach DNS; if one did, it would resolve nowhere useful.
        val resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups += hostname
                return listOf(InetAddress.getByName("10.9.9.9"))
            }
        }
        val gate = NetGate(NetConfig(appVersion = "test"), resolver)
        val urls = parseFixture("corpus/feeds/enclosure-private-address.xml").feed().episodes.mapNotNull { it.enclosure?.url }
        assertEquals(5, urls.size)
        for (url in urls) {
            val result = gate.fetch(url.toHttpUrl(), BodyLimit.IMAGE)
            val error = (result as? FetchResult.Failure)?.error
            assertTrue("$url gave $result", error is NetError.BlockedAddress)
        }
        assertTrue(lookups.isEmpty())
    }
}
