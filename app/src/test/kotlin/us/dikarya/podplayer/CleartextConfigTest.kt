/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * SPEC §3 allows http feeds and media, but Android refuses cleartext unless the app opts in. On the owner's phone
 * (2026-11-16) The Moth and The Field Guides never loaded ("CLEARTEXT communication … not permitted"). The address
 * filter in :core:net still guards where requests go; this only lets http through Android's own check.
 */
class CleartextConfigTest {
    private fun parse(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    @Test
    fun theManifestPointsAtTheNetworkSecurityConfig() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config", app.getAttributeNS(ANDROID, "networkSecurityConfig"))
    }

    @Test
    fun cleartextIsPermittedAndOnlySystemCertificatesAreTrusted() {
        val config = parse("src/main/res/xml/network_security_config.xml")
        val base = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        val sources = base.getElementsByTagName("certificates")
        val trusted = (0 until sources.length).map { (sources.item(it) as Element).getAttribute("src") }
        assertEquals("user-installed certificates stay untrusted", listOf("system"), trusted)
        assertTrue("no per-domain exceptions", config.getElementsByTagName("domain-config").length == 0)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
