/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.refresh

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import us.dikarya.podplayer.core.feed.FeedParseResult
import us.dikarya.podplayer.core.feed.FeedParser
import us.dikarya.podplayer.core.feed.xml.XmlParserProvider
import us.dikarya.podplayer.core.testing.Fixtures

/**
 * The feed corpus through the parser the app really uses (`Xml.newPullParser()`, here Robolectric's copy of
 * the platform one) gives the same results as through kxml2, which the `:core:feed` tests use (PROGRESS
 * "Later", S4/S5).
 */
@RunWith(AndroidJUnit4::class)
class AndroidParserCorpusTest {
    private val android = androidFeedParser()

    /**
     * kxml2 2.2.2's class files are too old for Robolectric's instrumentation (which claims `org.kxml2`), so
     * it is loaded child-first from its own jar; the `org.xmlpull` interface still comes from the sandbox.
     */
    private val kxmlClass: Class<*> = run {
        val jar = System.getProperty("java.class.path").split(File.pathSeparator).first { "kxml2-" in File(it).name }
        val loader = object : URLClassLoader(arrayOf(File(jar).toURI().toURL()), XmlPullParser::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("org.kxml2.")) return super.loadClass(name, resolve)
                synchronized(this) {
                    return findLoadedClass(name) ?: findClass(name)
                }
            }
        }
        loader.loadClass("org.kxml2.io.KXmlParser")
    }
    private val kxml = FeedParser(XmlParserProvider { kxmlClass.getDeclaredConstructor().newInstance() as XmlPullParser })

    private val fixtures = listOf(
        "corpus/feeds/bare-ampersands.xml", "corpus/feeds/doctype-with-entities.xml",
        "corpus/feeds/doctype-without-entities.xml", "corpus/feeds/duplicate-guids.xml",
        "corpus/feeds/enclosure-private-address.xml", "corpus/feeds/entity-expansion-bomb.xml",
        "corpus/feeds/external-entity.xml", "corpus/feeds/external-parameter-entity.xml",
        "corpus/feeds/html-instead-of-rss.html", "corpus/feeds/illegal-character-references.xml",
        "corpus/feeds/invalid-dates.xml", "corpus/feeds/javascript-links.xml", "corpus/feeds/not-a-feed.xml",
        "corpus/feeds/truncated.xml", "corpus/feeds/undeclared-itunes-prefix.xml", "corpus/feeds/utf8-bom.xml",
        "corpus/feeds/wrong-declared-encoding.xml", "samples/feeds/atom.xml", "samples/feeds/hosted-platform.xml",
        "samples/feeds/podcasting20.xml",
    )

    /** What matters about a result: the problem, or the episodes' identity and enclosures. */
    private fun summary(result: FeedParseResult): String = when (result) {
        is FeedParseResult.Failure -> "failure ${result.problem::class.simpleName}"
        is FeedParseResult.Success -> "feed ${result.feed.title} " + result.feed.episodes.joinToString { "${it.guid}|${it.title}|${it.enclosure?.url}" }
    }

    @Test
    fun theTwoParsersAreDifferentImplementations() {
        // Android repackages its kxml fork; if both were the same class, the comparison below would prove nothing.
        assertEquals("com.android.org.kxml2.io.KXmlParser", Xml.newPullParser().javaClass.name)
        assertEquals("org.kxml2.io.KXmlParser", kxmlClass.name)
    }

    @Test
    fun everyFixtureParsesTheSameWithBothParsers() {
        val differences = fixtures.mapNotNull { path ->
            val bytes = Fixtures.bytes(path)
            val a = summary(android.parse(bytes))
            val k = summary(kxml.parse(bytes))
            if (a == k) null else "$path\n  android: $a\n  kxml2:   $k"
        }
        assertEquals(differences.joinToString("\n"), emptyList<String>(), differences)
    }

    @Test
    fun everyPrefixOfEveryFixtureFailsTheSameWay() {
        val differences = mutableListOf<String>()
        for (path in fixtures) {
            val bytes = Fixtures.bytes(path)
            for (cut in bytes.indices step 13) {
                val prefix = bytes.copyOf(cut)
                val a = android.parse(prefix)
                val k = kxml.parse(prefix)
                if ((a is FeedParseResult.Success) != (k is FeedParseResult.Success)) {
                    differences += "$path cut at $cut: android ${summary(a).take(80)}, kxml2 ${summary(k).take(80)}"
                }
            }
        }
        assertEquals(differences.take(20).joinToString("\n"), 0, differences.size)
    }
}
