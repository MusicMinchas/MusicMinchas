package com.kike.musicplayer.data.discover

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** A story from an RSS or Atom feed. [source] / [sourceUrl]: the publisher, when the feed says (Google News does). */
data class FeedItem(
    val title: String,
    val link: String,
    val published: Long,
    val imageUrl: String?,
    val source: String?,
    val sourceUrl: String? = null,
)

/** Small RSS 2.0 / Atom reader for news feeds. */
object Feeds {
    fun parse(input: InputStream): List<FeedItem> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val items = mutableListOf<FeedItem>()
        var inItem = false
        var title: String? = null
        var link: String? = null
        var date: Long? = null
        var image: String? = null
        var source: String? = null
        var sourceUrl: String? = null
        var html: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> {
                        inItem = true; title = null; link = null; date = null; image = null; source = null; sourceUrl = null; html = null
                    }
                    !inItem -> Unit
                    name == "title" -> title = parser.nextText().trim()
                    name == "link" -> {
                        val href = parser.getAttributeValue(null, "href") // Atom: the page is rel="alternate" (or no rel)
                        val rel = parser.getAttributeValue(null, "rel")
                        if (href == null) link = parser.nextText().trim()
                        else if (rel == null || rel == "alternate") link = href
                    }
                    name == "pubDate" || name == "published" || name == "updated" || name == "dc:date" ->
                        parseDate(parser.nextText().trim())?.let { if (date == null) date = it }
                    name == "source" -> {
                        sourceUrl = parser.getAttributeValue(null, "url")
                        source = parser.nextText().trim()
                    }
                    name == "media:content" || name == "media:thumbnail" ->
                        if (image == null && parser.getAttributeValue(null, "medium").let { it == null || it == "image" }) {
                            image = parser.getAttributeValue(null, "url")
                        }
                    name == "enclosure" ->
                        if (image == null && parser.getAttributeValue(null, "type")?.startsWith("image") == true) {
                            image = parser.getAttributeValue(null, "url")
                        }
                    name == "description" || name == "content:encoded" || name == "content" || name == "summary" ->
                        if (html == null) html = runCatching { parser.nextText() }.getOrNull()
                }
                XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                    inItem = false
                    val t = title?.let { unescape(unescape(it)) } // Google News escapes some twice ("&amp;amp;")
                    val l = link
                    if (!t.isNullOrBlank() && !l.isNullOrBlank()) {
                        items += FeedItem(t, l, date ?: 0L, image ?: html?.let(::firstImage), source?.let(::unescape), sourceUrl)
                    }
                }
            }
        }
        return items
    }

    private val IMG = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private fun firstImage(html: String): String? = IMG.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&#8217;", "’").replace("&#8216;", "‘").replace("&#8220;", "“").replace("&#8221;", "”")
        .replace("&#8211;", "–").replace("&#8212;", "—").replace("&lt;", "<").replace("&gt;", ">")

    /** RFC 822 (RSS) or ISO 8601 (Atom). */
    fun parseDate(text: String): Long? {
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(text).toEpochMilli() }
        for (pattern in RFC822) {
            runCatching { return SimpleDateFormat(pattern, Locale.US).parse(text)?.time }
        }
        return null
    }

    private val RFC822 = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z")
}

/** Matching artist names in headlines and comparing titles. */
object Names {
    /**
     * Whether [text] names [artist] as a whole word, with the same capitals ("Muse" the band, not
     * "a muse"; "Opeth's" counts).
     */
    fun mentions(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(artist, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + artist.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = i + 1
        }
    }

    /**
     * Whether [text] names [artist] on its own somewhere, rather than inside a quoted title
     * ('Ghost Song', "Muse") or as part of another name (Ghost Hounds, Space Ghost, Strange Muse).
     * Headlines are often in Title Case, so a capitalised neighbour is fine when it's a usual
     * headline word ("Muse Announce…", "Ghost Frontman…", "Featuring Ghost"); "Ghost's" too. Words
     * like Song or Tour don't count: "Ghost Song" is a film, "Ghost Tour" a walk.
     */
    fun standsAlone(text: String, artist: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(artist, from)
            if (i < 0) return false
            from = i + 1
            val end = i + artist.length
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(end)
            if ((before != null && before.isLetterOrDigit()) || (after != null && after.isLetterOrDigit())) continue
            if (before in QUOTES || (after in QUOTES && !text.startsWith("s", end + 1))) continue
            val previous = if (before == ' ') text.substring(0, i - 1).substringAfterLast(' ') else ""
            if (previous.firstOrNull()?.isUpperCase() == true && previous.lowercase() !in HEADLINE_WORDS) continue
            val next = if (after == ' ') text.substring(end + 1).substringBefore(' ').trimEnd(',', ':', '.', '!', '?') else ""
            if (next.firstOrNull()?.isUpperCase() == true && next.lowercase() !in HEADLINE_WORDS) continue
            return true
        }
    }

    private val QUOTES = setOf('\'', '"', '‘', '’', '“', '”')

    /** Words that sit next to a band name in Title Case headlines without being part of it. */
    private val HEADLINE_WORDS = setOf(
        "a", "an", "the", "and", "&", "or", "of", "with", "by", "from", "for", "to", "at", "on", "in", "as", "like", "than",
        "vs", "vs.", "x", "is", "are", "was", "were", "will", "have", "has", "had", "can", "could", "would", "should", "do",
        "does", "did", "not", "featuring", "feat.", "ft.", "ex-", "former", "watch", "watch:", "hear", "see", "meet",
        "join", "joins", "support", "supports", "supporting", "opening", "about", "after", "before", "without", "plus",
        "announce", "announces", "share", "shares", "release", "releases", "reveal", "reveals", "drop", "drops", "unveil",
        "unveils", "confirm", "confirms", "cancel", "cancels", "return", "returns", "add", "adds", "play", "plays",
        "perform", "performs", "debut", "debuts", "tease", "teases", "celebrate", "celebrates", "postpone", "postpones",
        "launch", "launches", "kick", "kicks", "bring", "brings", "cover", "covers", "detail", "details", "deliver",
        "delivers", "headline", "headlines", "premiere", "premieres", "sign", "signs", "team", "teams", "say", "says",
        "react", "reacts", "pay", "pays", "salute", "salutes", "honor", "honors", "mourn", "mourns", "live", "new",
        "frontman", "frontwoman", "singer", "vocalist", "guitarist", "drummer", "bassist", "keyboardist", "member",
        "members", "founder", "co-founder", "leader", "fans", "legends", "icons",
    )

    /** Lower case, no accents, letters and digits only: for comparing titles and names. */
    fun comparable(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * A release title without its edition notes, to tell that two services list the same release:
     * "Album (Live)", "Album [Deluxe]" and "Album - Remastered" all become "album".
     */
    fun releaseKey(title: String): String {
        var t = title.trim()
        while (true) {
            val stripped = t.replace(EDITION_NOTE, "").replace(DASH_NOTE, "").trim()
            if (stripped == t || stripped.isEmpty()) break
            t = stripped
        }
        return comparable(t)
    }

    private val EDITION_NOTE = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")
    private val DASH_NOTE = Regex("""\s+-\s+[^-]+$""")

    /** Google News headlines end in " - Source". */
    fun stripSource(title: String, source: String?): String =
        if (source != null && title.endsWith(" - $source")) title.dropLast(source.length + 3) else title

    /**
     * A MusicBrainz first-release date that is still to come: a full date after [today], or a
     * month after the current one ("2026-11"). A bare year says too little.
     */
    fun upcomingDate(date: String?, today: LocalDate): LocalDate? {
        if (date == null) return null
        return when (date.length) {
            10 -> runCatching { LocalDate.parse(date) }.getOrNull()?.takeIf { it.isAfter(today) }
            7 -> runCatching { LocalDate.parse("$date-01") }.getOrNull()?.takeIf { it.isAfter(today.withDayOfMonth(1)) }
            else -> null
        }
    }

    /** A calendar date (and wall-clock time) as the UTC instant the Discover items store. */
    fun localMillis(date: LocalDate, secondOfDay: Int = 0): Long =
        date.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000 + secondOfDay * 1000L
}

/**
 * Telling music news from other news for artists with everyday names (Ghost, Paradise Lost, Muse).
 * Google News has no topics in its search feed, so this relies on who published a story and on the
 * headline's words.
 */
object MusicNews {
    /** Headline words that place a story in music even when the artist's name is an ordinary word. */
    private val STRONG = Regex(
        """\b(bands?|albums?|songs?|singles?|EPs?|frontman|frontwoman|singer|vocalist|guitarist|drummer|bassist|keyboardist|""" +
            """musicians?|concerts?|setlist|gigs?|lyrics|metal|rock|discography|songwriter)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Weaker hints, only for measuring how musical an artist's news is ("tour" also means ghost tours). */
    private val WEAK = Regex(
        """\b(tours?|festival|headlin\w*|live|video|record|reunion|anniversary|stage|fans|music|show)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Publishers that only (or mostly) cover music: known sites, and hosts that say so. */
    private val MUSIC_HOST_PARTS = listOf(
        "music", "metal", "rock", "punk", "guitar", "loud", "riff", "kerrang", "blabbermouth", "billboard", "rollingstone",
        "consequence", "pitchfork", "stereogum", "revolver", "decibel", "theprp", "axs.com", "brooklynvegan", "spin.com",
        "nme.com", "uproxx", "moshville", "newnoise", "ghostcult", "distortedsound", "wallofsound", "knotfest", "setlist",
        "tinnitist", "ericalper", "antimusic", "chaoszine", "deadrhetoric", "bravewords", "exclaim.ca", "clashmusic",
        "diymag", "thequietus", "undertheradar", "altpress", "sputnikmusic", "angrymetalguy", "invisibleoranges",
        "heavyblog", "nocleansinging", "sonicperspectives", "everythingisnoise", "jambase", "songkick", "bandsintown",
    )

    fun isMusicSite(url: String?): Boolean {
        val host = url?.substringAfter("://")?.substringBefore('/')?.lowercase() ?: return false
        return MUSIC_HOST_PARTS.any { it in host }
    }

    /** Clearly about music: a music publisher, or a headline with an unmistakable music word. */
    fun isClearlyMusic(item: FeedItem) = isMusicSite(item.sourceUrl) || STRONG.containsMatchIn(item.title)

    private fun hintsMusic(item: FeedItem) = isClearlyMusic(item) || WEAK.containsMatchIn(item.title)

    /**
     * Whether an artist's name is ambiguous: fewer than half of the headlines naming it look like
     * music news (Metallica: 70%, Judas Priest: 86%; Ghost: 15%, Paradise Lost: 18%, Muse: 1%).
     */
    fun isAmbiguous(headlines: List<FeedItem>): Boolean =
        headlines.size >= MIN_SAMPLE && headlines.count(::hintsMusic) * 2 < headlines.size

    /** Added to the search for an ambiguous name, to bring the music stories up. */
    const val MUSIC_QUERY = "(band OR album OR song OR singer OR frontman OR guitarist OR concert OR metal OR rock)"

    private const val MIN_SAMPLE = 6
}

