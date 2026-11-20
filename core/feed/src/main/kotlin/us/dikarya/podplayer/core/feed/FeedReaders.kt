/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.feed

import us.dikarya.podplayer.core.feed.xml.SafeXmlReader
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy

/** Shared field readers: caps, whitespace rules and the URL policy. */
internal class FeedContext(val reader: SafeXmlReader) {
    var rejectedUrls = 0
        private set
    private var capped = 0

    val truncated: Int get() = capped + reader.truncated

    val name: String get() = reader.parser.name
    val kind: NamespaceKind get() = FeedParser.namespaceKind(reader.parser.namespace.orEmpty())

    fun skip() = reader.skip()

    fun attribute(name: String): String? = reader.attribute(name)

    /** Element text, trimmed, or null when blank. */
    fun plain(max: Int = FieldLimits.SHORT_TEXT): String? = reader.readText(max).trim().ifEmpty { null }

    /** Element text with runs of whitespace collapsed to one space: titles and names. */
    fun title(max: Int = FieldLimits.TITLE): String? = collapse(reader.readText(max * 4), max)

    /** Raw markup for the sanitizer, trimmed. */
    fun notes(): String? = reader.readMarkup(FieldLimits.NOTES).trim().ifEmpty { null }

    fun collapse(text: String?, max: Int): String? {
        val collapsed = text?.trim()?.replace(whitespace, " ")?.ifEmpty { null } ?: return null
        if (collapsed.length <= max) return collapsed
        capped++
        return collapsed.take(max)
    }

    /**
     * Element text that is a URL (`<link>`, `<url>`, `<logo>`): read whole and checked like [url]. Text longer
     * than any allowed URL is rejected, never shortened, since a cut URL can still look valid.
     */
    fun urlText(): String? {
        val text = reader.readWholeText(UrlPolicy.MAX_URL_LENGTH + URL_TEXT_SLACK)
        if (text == null) {
            rejectedUrls++
            return null
        }
        return url(text)
    }

    /** A feed-supplied URL that passes [UrlPolicy.checkFeedUrl], as written (trimmed), or null. */
    fun url(raw: String?): String? {
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return null
        return when (UrlPolicy.checkFeedUrl(trimmed)) {
            is UrlCheck.Allowed -> trimmed
            is UrlCheck.Rejected -> {
                rejectedUrls++
                null
            }
        }
    }

    fun <T> addCapped(list: MutableCollection<T>, item: T?) {
        if (item == null) return
        if (list.size < FieldLimits.LIST) list += item else capped++
    }

    /** A small non-negative integer (season, episode), or null. */
    fun smallInt(text: String?): Int? = text?.trim()?.takeIf { smallNumber.matches(it) }?.toInt()

    /** A positive byte count or bitrate hint, or null. */
    fun positiveLong(text: String?): Long? =
        text?.trim()?.takeIf { longNumber.matches(it) }?.toLongOrNull()?.takeIf { it > 0 }

    fun shortAttribute(name: String, max: Int = 200): String? =
        attribute(name)?.trim()?.ifEmpty { null }?.let { collapse(it, max) }

    private companion object {
        /** Whitespace around a URL in element text (pretty-printed feeds indent it). */
        const val URL_TEXT_SLACK = 1024

        val whitespace = Regex("\\s+")
        val smallNumber = Regex("^[0-9]{1,6}$")
        val longNumber = Regex("^[0-9]{1,18}$")
    }
}

/**
 * The first value a feed gives for a field wins, but a repeated element must still be read: a reader that
 * skipped it would leave its children to be taken for fields of the enclosing item or channel. Both arguments
 * are evaluated, so write `field = first(field, ctx.read())`, never `field ?: ctx.read()`.
 */
internal fun <T> first(current: T?, next: T?): T? = current ?: next

/** Channel-level fields common to RSS and Atom, including the iTunes and Podcasting 2.0 extensions. */
internal class ChannelBuilder(private val ctx: FeedContext, private val format: FeedFormat) {
    var title: String? = null
    var link: String? = null
    var description: String? = null
    var author: String? = null
    var ownerName: String? = null
    var itunesImage: String? = null
    var image: String? = null
    var fallbackImage: String? = null
    var summary: String? = null
    var subtitle: String? = null
    val categories = LinkedHashSet<String>()
    var podcastGuid: String? = null
    val funding = mutableListOf<Funding>()
    val persons = mutableListOf<Person>()
    var license: License? = null
    val episodes = mutableListOf<ParsedEpisode>()

    /** Handles the current element if it is an iTunes or Podcasting 2.0 channel tag. */
    fun extension(): Boolean {
        when (ctx.kind) {
            NamespaceKind.Itunes -> when (ctx.name) {
                "author" -> author = first(author, ctx.title(FieldLimits.SHORT_TEXT))
                "image" -> {
                    itunesImage = itunesImage ?: ctx.url(ctx.attribute("href") ?: ctx.attribute("url"))
                    ctx.skip()
                }
                "category" -> itunesCategory()
                "summary" -> summary = first(summary, ctx.notes())
                "subtitle" -> subtitle = first(subtitle, ctx.notes())
                "owner" -> ctx.reader.forEachChild {
                    if (ctx.kind == NamespaceKind.Itunes && ctx.name == "name") {
                        ownerName = first(ownerName, ctx.title(FieldLimits.SHORT_TEXT))
                    } else {
                        ctx.skip()
                    }
                }
                else -> ctx.skip()
            }
            NamespaceKind.Podcast -> when (ctx.name) {
                "guid" -> podcastGuid = first(podcastGuid, ctx.plain(200))
                "funding" -> {
                    val url = ctx.url(ctx.attribute("url"))
                    val text = ctx.title(FieldLimits.SHORT_TEXT)
                    if (url != null) ctx.addCapped(funding, Funding(url, text))
                }
                "person" -> {
                    val role = ctx.shortAttribute("role")
                    val group = ctx.shortAttribute("group")
                    val img = ctx.url(ctx.attribute("img"))
                    val href = ctx.url(ctx.attribute("href"))
                    val name = ctx.title(FieldLimits.SHORT_TEXT)
                    if (name != null) ctx.addCapped(persons, Person(name, role, group, img, href))
                }
                "license" -> {
                    val url = ctx.url(ctx.attribute("url"))
                    val identifier = ctx.title(FieldLimits.SHORT_TEXT)
                    if (license == null && (url != null || identifier != null)) license = License(identifier, url)
                }
                // podcast:locked is read and ignored (SPEC §4); so is everything we don't keep.
                else -> ctx.skip()
            }
            else -> return false
        }
        return true
    }

    /** `itunes:category text="..."`, possibly with nested subcategories. */
    private fun itunesCategory() {
        ctx.addCapped(categories, ctx.collapse(ctx.attribute("text"), FieldLimits.SHORT_TEXT))
        ctx.reader.forEachChild {
            if (ctx.kind == NamespaceKind.Itunes && ctx.name == "category") itunesCategory() else ctx.skip()
        }
    }

    fun build() = ParsedFeed(
        format = format,
        title = title,
        link = link,
        description = description ?: summary ?: subtitle,
        author = author ?: ownerName,
        imageUrl = itunesImage ?: image ?: fallbackImage,
        categories = categories.toList(),
        podcastGuid = podcastGuid,
        funding = funding.toList(),
        persons = persons.toList(),
        license = license,
        episodes = episodes.toList(),
    )
}

/** Item-level fields common to RSS items and Atom entries. */
internal class ItemBuilder(private val ctx: FeedContext) {
    var guid: String? = null
    var title: String? = null
    var date: Long? = null
    var fallbackDate: Long? = null
    val enclosures = mutableListOf<ParsedEnclosure>()
    var durationMs: Long? = null
    var content: String? = null
    var description: String? = null
    var summary: String? = null
    var image: String? = null
    var link: String? = null
    var itunesSeason: Int? = null
    var itunesEpisode: Int? = null
    var podcastSeason: Int? = null
    var podcastEpisode: Int? = null
    var chapters: Chapters? = null
    val transcripts = mutableListOf<Transcript>()
    val soundbites = mutableListOf<Soundbite>()
    val alternates = mutableListOf<AlternateEnclosure>()

    fun addEnclosure(url: String?, type: String?, length: String?) {
        val checked = ctx.url(url) ?: return
        ctx.addCapped(enclosures, ParsedEnclosure(checked, type?.trim()?.ifEmpty { null }?.take(200), ctx.positiveLong(length)))
    }

    /** Handles the current element if it is a `content:`, `itunes:`, `podcast:` or `dc:` item tag. */
    fun extension(): Boolean {
        when (ctx.kind) {
            NamespaceKind.Content -> if (ctx.name == "encoded") content = first(content, ctx.notes()) else ctx.skip()
            NamespaceKind.DublinCore -> if (ctx.name == "date") {
                fallbackDate = first(fallbackDate, FeedDates.parse(ctx.plain(200)))
            } else {
                ctx.skip()
            }
            NamespaceKind.Itunes -> when (ctx.name) {
                "duration" -> durationMs = first(durationMs, Durations.parseMs(ctx.plain(50)))
                "image" -> {
                    image = image ?: ctx.url(ctx.attribute("href") ?: ctx.attribute("url"))
                    ctx.skip()
                }
                "season" -> itunesSeason = first(itunesSeason, ctx.smallInt(ctx.plain(50)))
                "episode" -> itunesEpisode = first(itunesEpisode, ctx.smallInt(ctx.plain(50)))
                "summary" -> summary = first(summary, ctx.notes())
                else -> ctx.skip()
            }
            NamespaceKind.Podcast -> podcastTag()
            else -> return false
        }
        return true
    }

    private fun podcastTag() {
        when (ctx.name) {
            "season" -> podcastSeason = first(podcastSeason, ctx.smallInt(ctx.plain(50)))
            "episode" -> podcastEpisode = first(podcastEpisode, ctx.smallInt(ctx.plain(50)))
            "chapters" -> {
                val url = ctx.url(ctx.attribute("url"))
                if (chapters == null && url != null) chapters = Chapters(url, ctx.shortAttribute("type"))
                ctx.skip()
            }
            "transcript" -> {
                val url = ctx.url(ctx.attribute("url"))
                if (url != null) {
                    ctx.addCapped(
                        transcripts,
                        Transcript(url, ctx.shortAttribute("type"), ctx.shortAttribute("language", 40), ctx.shortAttribute("rel", 40)),
                    )
                }
                ctx.skip()
            }
            "soundbite" -> {
                val start = Durations.secondsToMs(ctx.attribute("startTime"))
                val duration = Durations.secondsToMs(ctx.attribute("duration"))
                val text = ctx.title()
                if (start != null && duration != null && duration > 0) ctx.addCapped(soundbites, Soundbite(start, duration, text))
            }
            "alternateEnclosure" -> alternate()
            else -> ctx.skip()
        }
    }

    private fun alternate() {
        val type = ctx.shortAttribute("type")
        val length = ctx.positiveLong(ctx.attribute("length"))
        val bitrate = ctx.attribute("bitrate")?.trim()?.substringBefore('.')?.let(ctx::positiveLong)
        val title = ctx.shortAttribute("title")
        val language = ctx.shortAttribute("lang", 40)
        val isDefault = ctx.attribute("default")?.trim().equals("true", ignoreCase = true)
        val sources = mutableListOf<String>()
        ctx.reader.forEachChild {
            if (ctx.kind == NamespaceKind.Podcast && ctx.name == "source") ctx.addCapped(sources, ctx.url(ctx.attribute("uri")))
            ctx.skip()
        }
        if (sources.isNotEmpty()) ctx.addCapped(alternates, AlternateEnclosure(type, length, bitrate, title, language, isDefault, sources))
    }

    fun build() = ParsedEpisode(
        guid = guid,
        title = title,
        publishedAt = date ?: fallbackDate,
        enclosure = chooseEnclosure(),
        durationMs = durationMs,
        notes = content ?: description ?: summary,
        imageUrl = image,
        link = link,
        season = itunesSeason ?: podcastSeason,
        episode = itunesEpisode ?: podcastEpisode,
        chapters = chapters,
        transcripts = transcripts.toList(),
        soundbites = soundbites.toList(),
        alternateEnclosures = alternates.toList(),
    )

    /** Types are hints, but when an item has several enclosures the audio one is the episode. */
    private fun chooseEnclosure(): ParsedEnclosure? =
        enclosures.firstOrNull { it.type?.startsWith("audio/", ignoreCase = true) == true }
            ?: enclosures.firstOrNull { it.type?.startsWith("image/", ignoreCase = true) != true }
            ?: enclosures.firstOrNull()
}

internal class RssReader(private val ctx: FeedContext) {
    fun read(): ParsedFeed {
        var feed: ParsedFeed? = null
        ctx.reader.forEachChild {
            if (feed == null && ctx.kind == NamespaceKind.None && ctx.name == "channel") feed = channel() else ctx.skip()
        }
        return feed ?: ParsedFeed(FeedFormat.Rss)
    }

    private fun channel(): ParsedFeed {
        val channel = ChannelBuilder(ctx, FeedFormat.Rss)
        ctx.reader.forEachChild {
            if (ctx.kind == NamespaceKind.None) {
                when (ctx.name) {
                    "title" -> channel.title = first(channel.title, ctx.title())
                    "link" -> channel.link = first(channel.link, ctx.urlText())
                    "description" -> channel.description = first(channel.description, ctx.notes())
                    "image" -> channel.image = first(channel.image, image())
                    "category" -> ctx.addCapped(channel.categories, ctx.title(FieldLimits.SHORT_TEXT))
                    "item" -> {
                        ctx.reader.countItem()
                        channel.episodes += item()
                    }
                    else -> ctx.skip()
                }
            } else if (!channel.extension()) {
                ctx.skip()
            }
        }
        return channel.build()
    }

    private fun image(): String? {
        var url: String? = null
        ctx.reader.forEachChild {
            if (ctx.kind == NamespaceKind.None && ctx.name == "url") url = first(url, ctx.urlText()) else ctx.skip()
        }
        return url
    }

    private fun item(): ParsedEpisode {
        val item = ItemBuilder(ctx)
        ctx.reader.forEachChild {
            if (ctx.kind == NamespaceKind.None) {
                when (ctx.name) {
                    "title" -> item.title = first(item.title, ctx.title())
                    "guid" -> item.guid = first(item.guid, ctx.plain(FieldLimits.GUID))
                    "pubDate" -> item.date = first(item.date, FeedDates.parse(ctx.plain(200)))
                    "link" -> item.link = first(item.link, ctx.urlText())
                    "description" -> item.description = first(item.description, ctx.notes())
                    "enclosure" -> {
                        item.addEnclosure(ctx.attribute("url"), ctx.attribute("type"), ctx.attribute("length"))
                        ctx.skip()
                    }
                    else -> ctx.skip()
                }
            } else if (!item.extension()) {
                ctx.skip()
            }
        }
        return item.build()
    }
}

/** Atom 1.0 basics (and the old 0.3 namespace), with the same iTunes and Podcasting 2.0 extensions. */
internal class AtomReader(private val ctx: FeedContext, private val atomNamespace: String) {
    companion object {
        private const val ATOM_03 = "http://purl.org/atom/ns#"

        fun isAtomNamespace(namespace: String) =
            namespace.isEmpty() || us.dikarya.podplayer.core.feed.xml.Namespaces.isAtom(namespace) ||
                namespace.equals(ATOM_03, ignoreCase = true)
    }

    private fun isCore() = ctx.reader.parser.namespace.orEmpty() == atomNamespace

    fun read(): ParsedFeed {
        val channel = ChannelBuilder(ctx, FeedFormat.Atom)
        ctx.reader.forEachChild {
            if (isCore()) {
                when (ctx.name) {
                    "title" -> channel.title = first(channel.title, ctx.title())
                    "subtitle", "tagline" -> channel.description = first(channel.description, ctx.notes())
                    "link" -> {
                        val rel = ctx.attribute("rel")?.trim()?.lowercase() ?: "alternate"
                        if (rel == "alternate") channel.link = channel.link ?: ctx.url(ctx.attribute("href"))
                        ctx.skip()
                    }
                    "author" -> channel.author = first(channel.author, personName())
                    "logo" -> channel.image = first(channel.image, ctx.urlText())
                    "icon" -> channel.fallbackImage = first(channel.fallbackImage, ctx.urlText())
                    "category" -> {
                        ctx.addCapped(channel.categories, ctx.collapse(ctx.attribute("term"), FieldLimits.SHORT_TEXT))
                        ctx.skip()
                    }
                    "entry" -> {
                        ctx.reader.countItem()
                        channel.episodes += entry()
                    }
                    else -> ctx.skip()
                }
            } else if (!channel.extension()) {
                ctx.skip()
            }
        }
        return channel.build()
    }

    private fun personName(): String? {
        var name: String? = null
        ctx.reader.forEachChild {
            if (isCore() && ctx.name == "name") name = first(name, ctx.title(FieldLimits.SHORT_TEXT)) else ctx.skip()
        }
        return name
    }

    private fun entry(): ParsedEpisode {
        val item = ItemBuilder(ctx)
        var atomSummary: String? = null
        ctx.reader.forEachChild {
            if (isCore()) {
                when (ctx.name) {
                    "id" -> item.guid = first(item.guid, ctx.plain(FieldLimits.GUID))
                    "title" -> item.title = first(item.title, ctx.title())
                    "published", "issued" -> item.date = first(item.date, FeedDates.parse(ctx.plain(200)))
                    "updated", "modified" -> item.fallbackDate = first(item.fallbackDate, FeedDates.parse(ctx.plain(200)))
                    "link" -> {
                        val rel = ctx.attribute("rel")?.trim()?.lowercase() ?: "alternate"
                        when (rel) {
                            "enclosure" -> item.addEnclosure(ctx.attribute("href"), ctx.attribute("type"), ctx.attribute("length"))
                            "alternate" -> item.link = item.link ?: ctx.url(ctx.attribute("href"))
                        }
                        ctx.skip()
                    }
                    "content" -> item.content = first(item.content, ctx.notes())
                    "summary" -> atomSummary = first(atomSummary, ctx.notes())
                    else -> ctx.skip()
                }
            } else if (!item.extension()) {
                ctx.skip()
            }
        }
        // Atom's summary ranks with RSS's description: after the full content, before itunes:summary.
        item.description = atomSummary
        return item.build()
    }
}
