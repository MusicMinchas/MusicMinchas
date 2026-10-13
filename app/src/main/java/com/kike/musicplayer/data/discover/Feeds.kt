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

/** A story from an RSS or Atom feed. [source]: the site, when the feed says (Google News does). */
data class FeedItem(val title: String, val link: String, val published: Long, val imageUrl: String?, val source: String?)

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
        var html: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> {
                        inItem = true; title = null; link = null; date = null; image = null; source = null; html = null
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
                    name == "source" -> source = parser.nextText().trim()
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
                    val t = title?.let(::unescape)
                    val l = link
                    if (!t.isNullOrBlank() && !l.isNullOrBlank()) {
                        items += FeedItem(t, l, date ?: 0L, image ?: html?.let(::firstImage), source?.let(::unescape))
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
