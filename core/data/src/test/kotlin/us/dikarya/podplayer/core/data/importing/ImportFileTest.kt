/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §12 "Import a list of shows": the import file another app's export writes (M1-S9c). Hostile input. */
class ImportFileTest {
    private fun ok(text: String) = ImportFile.parse(text.toByteArray()) as ImportFileResult.Ok

    private fun refused(text: String): ImportFileProblem = (ImportFile.parse(text.toByteArray()) as ImportFileResult.Refused).problem

    @Test
    fun aGoodFileIsRead() {
        val result = ok(
            """
            {"format":"podplayer-import","version":1,"source":"podbean","shows":[
              {"title":"Science Hour","feedUrl":"https://feeds.example.com/science.xml","episodes":[
                {"title":"Black holes","published":"2026-03-04","starred":true,"starredAt":1790000000000},
                {"title":"Quarks","published":"2026-03-11T08:30:00Z","played":true},
                {"title":"Comets","positionMs":754000}
              ]},
              {"title":"Gardening"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("Science Hour", "Gardening"), result.shows.map { it.title })
        val science = result.shows.first()
        assertEquals("https://feeds.example.com/science.xml", science.feedUrl)
        assertEquals(3, science.episodes.size)
        val (holes, quarks, comets) = science.episodes
        assertTrue(holes.starred)
        assertEquals(1_790_000_000_000L, holes.starredAt)
        assertEquals(java.time.LocalDate.parse("2026-03-04").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), holes.publishedAt)
        assertEquals(java.time.Instant.parse("2026-03-11T08:30:00Z").toEpochMilli(), quarks.publishedAt)
        assertTrue(quarks.played)
        assertEquals(754_000L, comets.positionMs)
        assertNull(comets.publishedAt)
        assertEquals(emptyList<ImportEpisode>(), result.shows[1].episodes)
    }

    @Test
    fun theWrongFormatOrANewerVersionIsRefused() {
        assertEquals(ImportFileProblem.NotAnImportFile, refused("""{"format":"something-else","version":1,"shows":[]}"""))
        assertEquals(ImportFileProblem.NotAnImportFile, refused("""{"version":1,"shows":[]}"""))
        assertEquals(ImportFileProblem.NewerVersion, refused("""{"format":"podplayer-import","version":2,"shows":[]}"""))
        assertEquals(ImportFileProblem.NotAnImportFile, refused("""{"format":"podplayer-import","version":0,"shows":[]}"""))
    }

    @Test
    fun brokenJsonIsRefusedNotThrown() {
        for (text in listOf("", "not json", "{", "[1,2,3]", "{\"format\":\"podplayer-import\",\"version\":\"one\"}", "${Char(0)}${Char(1)}","{\"format\":\"podplayer-import\",\"version\":1,\"shows\":{}}")) {
            assertEquals(text, ImportFileProblem.NotAnImportFile, refused(text))
        }
    }

    @Test
    fun oversizedFilesAreRefusedBeforeParsing() {
        val big = ByteArray(ImportFile.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(ImportFileProblem.TooLarge, (ImportFile.parse(big) as ImportFileResult.Refused).problem)
    }

    @Test
    fun tooManyShowsOrEpisodesIsRefused() {
        val shows = (1..ImportFile.MAX_SHOWS + 1).joinToString(",") { """{"title":"Show $it"}""" }
        assertEquals(ImportFileProblem.TooManyItems, refused("""{"format":"podplayer-import","version":1,"shows":[$shows]}"""))
        val episodes = (1..ImportFile.MAX_EPISODES + 1).joinToString(",") { """{"title":"E$it"}""" }
        assertEquals(ImportFileProblem.TooManyItems, refused("""{"format":"podplayer-import","version":1,"shows":[{"title":"S","episodes":[$episodes]}]}"""))
    }

    @Test
    fun deeplyNestedJunkDoesNotCrash() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val result = ImportFile.parse("""{"format":"podplayer-import","version":1,"junk":$deep,"shows":[]}""".toByteArray())
        assertTrue(result is ImportFileResult.Ok || result is ImportFileResult.Refused)
    }

    @Test
    fun aDateTooFarOutForMillisecondsIsNoDateNotACrash() {
        // Codex review (PR 3): Instant.parse accepted these, then toEpochMilli() threw ArithmeticException.
        val result = ok(
            """
            {"format":"podplayer-import","version":1,"shows":[{"title":"Far","episodes":[
              {"title":"Z","published":"+999999999-12-31T23:59:59Z"},
              {"title":"Offset","published":"+999999999-12-31T23:59:59-18:00"},
              {"title":"Past","published":"-999999999-01-01T00:00:00Z"}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(listOf(null, null, null), result.shows.single().episodes.map { it.publishedAt })
    }

    @Test
    fun hostileValuesAreCleanedOrDropped() {
        val bell = Char(7)
        val rlo = Char(0x202E)
        val longTitle = "x".repeat(5_000)
        val result = ok(
            """
            {"format":"podplayer-import","version":1,"shows":[
              {"title":"Evil$bell$rlo Show","feedUrl":"javascript:alert(1)","episodes":[
                {"title":"$longTitle","positionMs":-5,"published":"yesterday"},
                {"title":"Fine","positionMs":99999999999999},
                {"title":"   "}
              ]},
              {"title":"  ","feedUrl":"file:///etc/passwd"},
              {"feedUrl":"https://ok.example.com/rss"}
            ]}
            """.trimIndent(),
        )
        val evil = result.shows.first()
        assertEquals("Evil Show", evil.title)
        assertNull("only http and https feed addresses", evil.feedUrl)
        assertEquals(ImportFile.MAX_TEXT, evil.episodes[0].title!!.length)
        assertNull(evil.episodes[0].positionMs)
        assertNull("an unreadable date is no date", evil.episodes[0].publishedAt)
        assertNull("positions past a week are nonsense", evil.episodes[1].positionMs)
        assertEquals("an episode with no title can't be matched", 2, evil.episodes.size)
        assertEquals("a show with neither a name nor a usable address is dropped", 2, result.shows.size)
        assertEquals(1, result.dropped)
        assertEquals("https://ok.example.com/rss", result.shows[1].feedUrl)
    }

    @Test
    fun looksLikeAnImportFile() {
        assertTrue(ImportFile.looksLikeOne("  {\"format\":\"podplayer-import\", ..."))
        assertTrue(!ImportFile.looksLikeOne("Science Hour\nGardening"))
    }

    @Test
    fun aValidFileWithItsFormatFarFromTheStartIsStillAnImportFile() {
        // CodeRabbit review (PR 3): only the first 200 characters were searched for the format, so such a file was
        // read as a list of shows, and its lines (feed addresses included) would have been searched as names.
        val text = """{"source":"${"s".repeat(250)}","shows":[{"feedUrl":"https://private.example.com/rss?token=SECRET","episodes":[]}],"format":"podplayer-import","version":1}"""
        assertTrue(ImportFile.looksLikeOne(text))
        val result = ImportFile.parse(text.toByteArray()) as ImportFileResult.Ok
        assertEquals("https://private.example.com/rss?token=SECRET", result.shows.single().feedUrl)
    }
}
