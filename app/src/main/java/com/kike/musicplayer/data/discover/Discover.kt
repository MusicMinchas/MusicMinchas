package com.kike.musicplayer.data.discover

import android.util.Log
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.db.ArtistInfoDao
import com.kike.musicplayer.data.db.DiscoverDao
import com.kike.musicplayer.data.db.DiscoverItem
import com.kike.musicplayer.data.db.FollowedArtist
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The Discover tab's content for the artists the user follows, fetched at most every few hours
 * and kept in the database (so it shows at once, and offline):
 * - releases of the last 90 days and announced ones: Deezer, plus MusicBrainz for albums with a
 *   future release date that Deezer doesn't list yet;
 * - concerts in the chosen country: Ticketmaster Discovery API;
 * - news: Google News search per artist, plus music sites' feeds, keeping headlines that name the
 *   artist.
 * A source that fails keeps what it found last time.
 */
class DiscoverRepository(
    private val dao: DiscoverDao,
    private val artistInfo: ArtistInfoDao,
    private val settings: Settings,
    private val http: OkHttpClient,
    private val moshi: Moshi,
    private val ticketmasterKey: String,
    private val scope: CoroutineScope,
) {
    val followed: Flow<List<FollowedArtist>> = dao.observeFollowed()
    val items: Flow<List<DiscoverItem>> = dao.observeItems()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val mutex = Mutex()
    private var feedCache: Pair<Long, List<FeedItem>>? = null

    fun follow(name: String) = scope.launch {
        dao.follow(FollowedArtist(name, System.currentTimeMillis()))
        mutex.withLock { refreshArtist(name, siteNews()) }
    }

    fun unfollow(name: String) = scope.launch { dao.unfollow(name) }

    /** Fetches everything again when [force] or when the last refresh is older than a few hours. */
    fun refresh(force: Boolean) {
        if (mutex.isLocked) return
        if (!force && System.currentTimeMillis() - settings.discoverRefreshedAt < REFRESH_EVERY_MS) return
        scope.launch {
            mutex.withLock {
                _refreshing.value = true
                try {
                    if (force) feedCache = null
                    val news = siteNews()
                    for (artist in dao.followed()) refreshArtist(artist.name, news)
                    settings.discoverRefreshedAt = System.currentTimeMillis()
                } finally {
                    _refreshing.value = false
                }
            }
        }
    }

    /** The concert country changed: concerts again, nothing else. */
    fun refreshConcerts() = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            dao.clearKind(DiscoverItem.CONCERT)
            for (a in dao.followed()) {
                runCatching { concerts(a) }.onSuccess { dao.replace(a.name, DiscoverItem.CONCERT, it) }
                    .onFailure { Log.i(TAG, "Concerts of ${a.name}: $it") }
            }
        }
    }

    private suspend fun refreshArtist(name: String, siteNews: List<FeedItem>) = withContext(Dispatchers.IO) {
        val artist = dao.followed(name) ?: return@withContext
        runCatching { releases(artist) }.onSuccess { dao.replace(name, DiscoverItem.RELEASE, it) }
            .onFailure { Log.i(TAG, "Releases of $name: $it") }
        dao.followed(name)?.let { a ->
            runCatching { concerts(a) }.onSuccess { dao.replace(name, DiscoverItem.CONCERT, it) }
                .onFailure { Log.i(TAG, "Concerts of $name: $it") }
        }
        runCatching { news(name, siteNews) }.onSuccess { dao.replace(name, DiscoverItem.NEWS, it) }
            .onFailure { Log.i(TAG, "News of $name: $it") }
    }

    // ---- Releases ----

    private suspend fun releases(artist: FollowedArtist): List<DiscoverItem> {
        val today = LocalDate.now()
        val deezer = deezerReleases(artist, today)
        val known = deezer.mapTo(HashSet()) { Names.releaseKey(it.title) }
        val announced = runCatching { announcedReleases(artist.name, today) }
            .onFailure { Log.i(TAG, "MusicBrainz ${artist.name}: $it") }.getOrDefault(emptyList())
            .filter { Names.releaseKey(it.title) !in known }
        return deezer + announced
    }

    private suspend fun deezerReleases(artist: FollowedArtist, today: LocalDate): List<DiscoverItem> {
        val id = artist.deezerId ?: deezerId(artist.name).also { dao.update(artist.copy(deezerId = it)) }
        if (id == 0L) return emptyList()
        val since = today.minusDays(RELEASE_DAYS)
        val out = mutableListOf<DiscoverItem>()
        var url: String? = "https://api.deezer.com/artist/$id/albums?limit=100"
        var pages = 0
        while (url != null && pages++ < 5) {
            val page = json(url)
            for (album in page["data"] as? List<*> ?: emptyList<Any>()) {
                val a = album as? Map<*, *> ?: continue
                val date = (a["release_date"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
                if (date.isBefore(since)) continue
                out += DiscoverItem(
                    id = "dz:${(a["id"] as? Number)?.toLong()}",
                    kind = DiscoverItem.RELEASE,
                    artist = artist.name,
                    title = a["title"] as? String ?: continue,
                    detail = recordType(a["record_type"] as? String),
                    date = Names.localMillis(date),
                    imageUrl = a["cover_xl"] as? String ?: a["cover_big"] as? String,
                    url = a["link"] as? String,
                    upcoming = date.isAfter(today),
                )
            }
            url = page["next"] as? String
            delay(DEEZER_INTERVAL_MS)
        }
        return out.distinctBy { Names.comparable(it.title) }
    }

    /** Deezer's artist with exactly this name (accents and case aside), or 0. */
    private fun deezerId(name: String): Long {
        val url = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
            .addQueryParameter("q", name).addQueryParameter("limit", "5").build()
        val data = json(url.toString())["data"] as? List<*> ?: return 0
        return data.asSequence().mapNotNull { it as? Map<*, *> }
            .firstOrNull { Names.comparable(it["name"] as? String ?: "") == Names.comparable(name) }
            ?.let { (it["id"] as? Number)?.toLong() } ?: 0
    }

    private fun recordType(type: String?) = when (type) {
        "album" -> "Album"
        "single" -> "Single"
        "ep" -> "EP"
        "compile" -> "Compilation"
        else -> type?.replaceFirstChar { it.uppercase() }
    }

    /** Release groups with a first-release date still to come, from MusicBrainz (by the artist's MBID). */
    private suspend fun announcedReleases(name: String, today: LocalDate): List<DiscoverItem> {
        val mbid = artistInfo.get(name.lowercase(Locale.ROOT))?.mbid ?: return emptyList()
        val url = "https://musicbrainz.org/ws/2/release-group".toHttpUrl().newBuilder()
            .addQueryParameter("query", "arid:$mbid AND firstreleasedate:[$today TO 2100-12-31]")
            .addQueryParameter("limit", "25").addQueryParameter("fmt", "json").build()
        delay(MUSICBRAINZ_INTERVAL_MS) // 1 request a second
        val groups = json(url.toString())["release-groups"] as? List<*> ?: return emptyList()
        return groups.mapNotNull { g ->
            val m = g as? Map<*, *> ?: return@mapNotNull null
            val date = Names.upcomingDate(m["first-release-date"] as? String, today) ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            val types = listOfNotNull(m["primary-type"] as? String) + (m["secondary-types"] as? List<*>).orEmpty().filterIsInstance<String>()
            DiscoverItem(
                id = "mb:$id", kind = DiscoverItem.RELEASE, artist = name,
                title = m["title"] as? String ?: return@mapNotNull null,
                detail = types.joinToString(" · ").ifEmpty { null },
                date = Names.localMillis(date),
                imageUrl = "https://coverartarchive.org/release-group/$id/front-500",
                url = "https://musicbrainz.org/release-group/$id",
                upcoming = true,
            )
        }.distinctBy { Names.comparable(it.title) }
    }

    // ---- Concerts ----

    private suspend fun concerts(artist: FollowedArtist): List<DiscoverItem> {
        if (ticketmasterKey.isBlank()) return emptyList()
        val id = artist.ticketmasterId ?: ticketmasterId(artist.name).also { dao.update(artist.copy(ticketmasterId = it)) }
        if (id.isEmpty()) return emptyList()
        val url = "https://app.ticketmaster.com/discovery/v2/events.json".toHttpUrl().newBuilder()
            .addQueryParameter("attractionId", id)
            .addQueryParameter("countryCode", settings.concertCountry.value)
            .addQueryParameter("sort", "date,asc")
            .addQueryParameter("size", "50")
            .addQueryParameter("apikey", ticketmasterKey).build()
        delay(TICKETMASTER_INTERVAL_MS)
        val events = (json(url.toString())["_embedded"] as? Map<*, *>)?.get("events") as? List<*> ?: return emptyList()
        return events.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val dates = m["dates"] as? Map<*, *>
            if ((dates?.get("status") as? Map<*, *>)?.get("code") == "cancelled") return@mapNotNull null
            val start = dates?.get("start") as? Map<*, *>
            val day = (start?.get("localDate") as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val time = (start["localTime"] as? String)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val venue = ((m["_embedded"] as? Map<*, *>)?.get("venues") as? List<*>)?.firstOrNull() as? Map<*, *>
            val city = (venue?.get("city") as? Map<*, *>)?.get("name") as? String
            DiscoverItem(
                id = "tm:${m["id"]}", kind = DiscoverItem.CONCERT, artist = artist.name,
                title = m["name"] as? String ?: artist.name,
                detail = listOfNotNull(venue?.get("name") as? String, city).joinToString(" · ").ifEmpty { null },
                date = Names.localMillis(day, time?.toSecondOfDay() ?: 0),
                imageUrl = bestImage(m["images"] as? List<*>),
                url = m["url"] as? String,
            )
        }.distinctBy { it.date to it.detail } // the same show is sometimes listed twice (e.g. with VIP packages)
    }

    /**
     * Ticketmaster's performer: the one linked to the artist's MusicBrainz entry, else the one with
     * exactly this name. "" when there is none.
     */
    private suspend fun ticketmasterId(name: String): String {
        val url = "https://app.ticketmaster.com/discovery/v2/attractions.json".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", name)
            .addQueryParameter("classificationName", "music")
            .addQueryParameter("size", "20")
            .addQueryParameter("apikey", ticketmasterKey).build()
        delay(TICKETMASTER_INTERVAL_MS)
        val list = ((json(url.toString())["_embedded"] as? Map<*, *>)?.get("attractions") as? List<*>)
            .orEmpty().filterIsInstance<Map<*, *>>()
        val mbid = artistInfo.get(name.lowercase(Locale.ROOT))?.mbid
        fun mbids(a: Map<*, *>) = (((a["externalLinks"] as? Map<*, *>)?.get("musicbrainz") as? List<*>).orEmpty())
            .mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
        val match = mbid?.let { id -> list.firstOrNull { id in mbids(it) } }
            ?: list.firstOrNull { Names.comparable(it["name"] as? String ?: "") == Names.comparable(name) }
        return match?.get("id") as? String ?: ""
    }

    private fun bestImage(images: List<*>?): String? = images.orEmpty().filterIsInstance<Map<*, *>>()
        .filter { it["ratio"] == "16_9" }
        .minByOrNull { kotlin.math.abs(((it["width"] as? Number)?.toInt() ?: 0) - 640) }
        ?.get("url") as? String

    // ---- News ----

    private fun news(name: String, siteNews: List<FeedItem>): List<DiscoverItem> {
        val since = System.currentTimeMillis() - NEWS_DAYS * DAY_MS
        val plain = runCatching { googleSearch("\"$name\"", name) }.onFailure { Log.i(TAG, "Google News $name: $it") }.getOrDefault(emptyList())
        val ambiguous = MusicNews.isAmbiguous(plain)
        val google = if (!ambiguous) plain else runCatching { musicNews(name, plain) }.getOrDefault(emptyList())
        // The music sites only cover music, but "Ghost" can still be Ghost Hounds there.
        val sites = siteNews.filter { Names.mentions(it.title, name) && (!ambiguous || Names.standsAlone(it.title, name)) }
        return (sites + google.sortedByDescending { it.published }.take(GOOGLE_PER_ARTIST))
            .filter { it.published >= since }
            .sortedByDescending { it.published }
            .distinctBy { Names.comparable(it.title) }
            .map { item ->
                DiscoverItem(
                    id = "news:${item.link.hashCode()}:${name.hashCode()}", kind = DiscoverItem.NEWS, artist = name,
                    title = item.title, detail = item.source, date = item.published, imageUrl = item.imageUrl, url = item.link,
                )
            }
    }

    /**
     * For a name that is also an everyday word or a title (Ghost, Paradise Lost, Muse) most
     * headlines naming it aren't about the band: search again for music stories, and keep those
     * clearly about music that name the band on its own ([MusicNews], [Names.standsAlone]).
     */
    private fun musicNews(name: String, plain: List<FeedItem>): List<FeedItem> =
        (googleSearch("\"$name\" ${MusicNews.MUSIC_QUERY}", name) + plain)
            .filter { MusicNews.isClearlyMusic(it) && Names.standsAlone(it.title, name) }
            .distinctBy { it.link }

    private fun googleSearch(query: String, name: String): List<FeedItem> {
        val url = "https://news.google.com/rss/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$query when:${NEWS_DAYS}d")
            .addQueryParameter("hl", "en-US").addQueryParameter("gl", "US").addQueryParameter("ceid", "US:en").build()
        return feed(url.toString())
            .map { it.copy(title = Names.stripSource(it.title, it.source)) }
            // The search also matches the name in the article text; keep headlines about the artist.
            .filter { Names.mentions(it.title, name) && it.source?.equals("YouTube", ignoreCase = true) != true }
    }

    /** The music sites' latest stories, read once an hour at most. */
    private fun siteNews(): List<FeedItem> {
        feedCache?.let { (at, items) -> if (System.currentTimeMillis() - at < FEED_CACHE_MS) return items }
        val items = SITE_FEEDS.flatMap { (site, url) ->
            runCatching { feed(url).map { it.copy(source = site) } }.onFailure { Log.i(TAG, "$site feed: $it") }.getOrDefault(emptyList())
        }
        feedCache = System.currentTimeMillis() to items
        return items
    }

    private fun feed(url: String): List<FeedItem> =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            Feeds.parse(response.body.byteStream())
        }

    private fun json(url: String): Map<*, *> =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            moshi.adapter(Map::class.java).fromJson(response.body.string()) ?: emptyMap<Any, Any>()
        }

    companion object {
        private const val TAG = "Discover"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val REFRESH_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val FEED_CACHE_MS = 60 * 60 * 1000L
        const val RELEASE_DAYS = 90L
        const val NEWS_DAYS = 30L
        private const val GOOGLE_PER_ARTIST = 8
        private const val DEEZER_INTERVAL_MS = 150L
        private const val MUSICBRAINZ_INTERVAL_MS = 1100L
        private const val TICKETMASTER_INTERVAL_MS = 250L

        /** Rock and metal sites, plus general music news; headlines are matched against followed names. */
        private val SITE_FEEDS = listOf(
            "Blabbermouth" to "https://blabbermouth.net/feed",
            "Metal Injection" to "https://metalinjection.net/feed",
            "MetalSucks" to "https://www.metalsucks.net/feed/",
            "Loudwire" to "https://loudwire.com/feed/",
            "Consequence" to "https://consequence.net/feed/",
            "NME" to "https://www.nme.com/feed",
        )
    }
}
