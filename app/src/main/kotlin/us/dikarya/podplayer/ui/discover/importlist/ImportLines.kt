/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover.importlist

import kotlin.math.max
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.dikarya.podplayer.core.data.importing.ImportText
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy
import us.dikarya.podplayer.core.net.redact
import us.dikarya.podplayer.ui.discover.DiscoverInput

/** One line of a pasted or shared list (SPEC §12). */
sealed interface ImportLine {
    /** A show name, searched for on Apple's podcast search. */
    data class Name(val text: String) : ImportLine

    /** A feed address, already through the URL policy (canonical form). Never fetched before the owner confirms. */
    data class Address(val url: String) : ImportLine {
        override fun toString() = "Address(${redact(url)})"
    }

    /** A podcasts.apple.com link: looked up by its id on Apple's search API. */
    data class AppleId(val id: Long) : ImportLine
}

/** The usable lines, and how many were left out and why (the screen says so; nothing is lost silently). */
data class ImportList(val lines: List<ImportLine>, val duplicates: Int = 0, val tooLong: Int = 0, val refused: Int = 0)

sealed interface ImportLinesResult {
    data class Ok(val list: ImportList) : ImportLinesResult

    data object Empty : ImportLinesResult

    /** More than [ImportLines.MAX_LINES] shows: refused as a whole (CLAUDE.md: on a breach, reject). */
    data object TooMany : ImportLinesResult

    data object TooLarge : ImportLinesResult
}

/** Splits pasted or shared text into show names and links. Every line is untrusted input. */
object ImportLines {
    const val MAX_LINES = 500
    const val MAX_LINE = 300
    const val MAX_CHARS = 256 * 1024

    private val newline = Regex("\r\n|\n|\r")
    private val bullet = Regex("^(?:[-*•·–]|\\d{1,3}[.)])\\s+")
    private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val appleId = Regex("/id(\\d{1,18})(?:$|[/?#])")
    private val nonLinkSchemes = setOf("javascript", "vbscript", "data", "file", "content", "intent", "mailto", "about", "ftp")

    fun parse(text: String): ImportLinesResult {
        if (text.length > MAX_CHARS) return ImportLinesResult.TooLarge
        val lines = ArrayList<ImportLine>()
        val seen = HashSet<String>()
        var any = false
        var duplicates = 0
        var tooLong = 0
        var refused = 0
        for (raw in text.split(newline)) {
            val cleaned = ImportText.clean(raw, max = Int.MAX_VALUE) ?: continue
            any = true
            val line = cleaned.replaceFirst(bullet, "").trim()
            if (line.isEmpty()) continue
            if (line.length > MAX_LINE) {
                tooLong++
                continue
            }
            val parsed = classify(line)
            if (parsed == null) {
                refused++
                continue
            }
            if (!seen.add(key(parsed))) {
                duplicates++
                continue
            }
            lines += parsed
            if (lines.size > MAX_LINES) return ImportLinesResult.TooMany
        }
        if (!any) return ImportLinesResult.Empty
        return ImportLinesResult.Ok(ImportList(lines, duplicates, tooLong, refused))
    }

    /** Null for a link the app may not use. */
    private fun classify(line: String): ImportLine? {
        val oneWord = line.none { it.isWhitespace() }
        if (oneWord) {
            val scheme = schemePrefix.find(line)?.groupValues?.get(1)?.lowercase()
            if (scheme != null && scheme != "http" && scheme != "https" && (scheme in nonLinkSchemes || "//" in line)) return null
        }
        val input = DiscoverInput.of(line)
        if (input !is DiscoverInput.FeedUrl) return ImportLine.Name(line)
        val url = (UrlPolicy.checkFeedUrl(input.url) as? UrlCheck.Allowed)?.url ?: return null
        if (url.host == "podcasts.apple.com" || url.host == "itunes.apple.com") {
            appleId.find(url.encodedPath)?.groupValues?.get(1)?.toLongOrNull()?.let { return ImportLine.AppleId(it) }
        }
        return ImportLine.Address(url.toString())
    }

    private fun key(line: ImportLine): String = when (line) {
        is ImportLine.Name -> "n:" + line.text.lowercase()
        is ImportLine.Address -> "u:" + (line.url.toHttpUrlOrNull()?.toString() ?: line.url)
        is ImportLine.AppleId -> "a:" + line.id
    }
}

/**
 * Spaces requests at least [intervalMs] apart (Apple's search allows about 20 a minute). [slowDown] doubles the
 * interval after Apple says "too many", up to [MAX_INTERVAL_MS]; [relax] halves it again after a success, never
 * below where it started. Its clock ([now], [waitUntil]) is the importer's only clock, so tests run on fake time.
 */
class Pacer(
    private var intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val startMs = intervalMs
    private var last: Long? = null

    fun now(): Long = clock()

    suspend fun await() {
        val previous = last
        if (previous != null) {
            val wait = previous + intervalMs - now()
            if (wait > 0) sleep(wait)
        }
        last = now()
    }

    /** Waits until [time] on this clock; a time already past returns at once. */
    suspend fun waitUntil(time: Long) {
        val wait = time - now()
        if (wait > 0) sleep(wait)
    }

    fun slowDown() {
        intervalMs = max(intervalMs * 2, 1).coerceAtMost(MAX_INTERVAL_MS)
    }

    fun relax() {
        intervalMs = max(intervalMs / 2, startMs)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 3_000L
        const val MAX_INTERVAL_MS = 60_000L
    }
}
