/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover.importlist

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC §12 "Import a list of shows": lines are untrusted input with limits on count and length. */
class ImportLinesTest {
    private fun lines(text: String) = (ImportLines.parse(text) as ImportLinesResult.Ok).list

    @Test
    fun namesAndLinksOnePerLine() {
        val list = lines(
            """
            Science Hour
              - Gardening Today
            * The Daily Thing
            1. Numbered Show
            2) Another Numbered
            • Bulleted
            https://feeds.example.com/rss?token=abc
            feeds.example.org/podcast.xml
            https://podcasts.apple.com/us/podcast/some-show/id1234567890?i=1000
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ImportLine.Name("Science Hour"),
                ImportLine.Name("Gardening Today"),
                ImportLine.Name("The Daily Thing"),
                ImportLine.Name("Numbered Show"),
                ImportLine.Name("Another Numbered"),
                ImportLine.Name("Bulleted"),
                ImportLine.Address("https://feeds.example.com/rss?token=abc"),
                ImportLine.Address("https://feeds.example.org/podcast.xml"),
                ImportLine.AppleId(1234567890),
            ),
            list.lines,
        )
    }

    @Test
    fun blankAndDuplicateLinesAreDroppedAndCounted() {
        val list = lines("Science Hour\n\n   \nscience hour\nSCIENCE  HOUR\nGardening\r\nGardening")
        assertEquals(listOf(ImportLine.Name("Science Hour"), ImportLine.Name("Gardening")), list.lines)
        assertEquals(3, list.duplicates)
    }

    @Test
    fun controlAndBidiCharactersAreRemoved() {
        val text = "Evil${Char(7)}${Char(0x202E)} Show${Char(0)}\n${Char(0x200B)}"
        assertEquals(listOf(ImportLine.Name("Evil Show")), lines(text).lines)
    }

    @Test
    fun overlongLinesAreSkippedNotCut() {
        val list = lines("Fine\n" + "x".repeat(ImportLines.MAX_LINE + 1) + "\nhttps://example.com/" + "a".repeat(ImportLines.MAX_LINE))
        assertEquals(listOf(ImportLine.Name("Fine")), list.lines)
        assertEquals(2, list.tooLong)
    }

    @Test
    fun linksTheAppMayNotOpenAreRefused() {
        val list = lines("javascript:alert(1)\nfile:///etc/passwd\nftp://example.com/feed\nhttp://2130706433/feed\nOK Show")
        assertEquals(listOf(ImportLine.Name("OK Show")), list.lines)
        assertEquals(4, list.refused)
    }

    @Test
    fun overFiveHundredShowsIsRefusedAsAWhole() {
        val ok = (1..ImportLines.MAX_LINES).joinToString("\n") { "Show $it" }
        assertEquals(ImportLines.MAX_LINES, lines(ok).lines.size)
        assertEquals(ImportLinesResult.TooMany, ImportLines.parse("$ok\nOne more"))
        // Blank lines don't count against the limit.
        assertEquals(ImportLines.MAX_LINES, lines(ok + "\n\n\n").lines.size)
    }

    @Test
    fun hugeTextIsRefusedBeforeSplitting() {
        assertEquals(ImportLinesResult.TooLarge, ImportLines.parse("a".repeat(ImportLines.MAX_CHARS + 1)))
        assertEquals(ImportLinesResult.Empty, ImportLines.parse(" \n\n "))
    }
}
