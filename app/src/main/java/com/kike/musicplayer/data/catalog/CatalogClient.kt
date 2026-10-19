package com.kike.musicplayer.data.catalog

import android.util.Log
import com.kike.musicplayer.data.discover.Names
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

data class CatalogArtist(
    val id: String,
    val name: String,
    val pictureUrl: String?,
    val albumCount: Int? = null,
    val source: String,
)

data class CatalogAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val coverUrl: String?,
    val genre: String?,
    val trackCount: Int?,
    val recordType: String = "album",
    val source: String,
)

data class CatalogTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val coverUrl: String?,
    val albumId: String?,
    val source: String,
)

data class CatalogPlaylist(
    val id: String,
    val title: String,
    val creator: String?,
    val trackCount: Int,
    val coverUrl: String?,
    val source: String = SOURCE_DEEZER,
) {
    companion object {
        const val SOURCE_DEEZER = "deezer"
        const val SOURCE_APPLE = "apple"
    }
}

/**
 * Public, zero-account catalog & metadata client for Deezer (`api.deezer.com`) and Apple Music /
 * iTunes (`itunes.apple.com`). Never streams or plays audio; used strictly for catalog search,
 * public playlist discovery, artist discography comparison, and dynamic metadata/artwork lookup.
 */
class CatalogClient(
    private val http: OkHttpClient,
    private val moshi: Moshi,
) {
    private val deezerMutex = Mutex()
    private var lastDeezerCall = 0L

    private val appleMutex = Mutex()
    private var lastAppleCall = 0L

    // ---- Dynamic Metadata & Cover Art Lookups (Step 1) ----

    /** Square 1000x1000 artist photo from Deezer on exact comparable name match. */
    suspend fun deezerArtistPhoto(artist: String): String? = withContext(Dispatchers.IO) {
        val url = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
            .addQueryParameter("q", artist)
            .addQueryParameter("limit", "5")
            .build()
        val data = deezerJson(url.toString())["data"] as? List<*> ?: return@withContext null
        val target = Names.comparable(artist)
        data.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { Names.comparable(it["name"] as? String ?: "") == target }
            ?.let { (it["picture_xl"] as? String) ?: (it["picture_big"] as? String) }
            ?.takeIf { "/artist//" !in it }
    }

    /** Album cover (1000x1000), release year and genre from Deezer for [album] by [artist]. */
    suspend fun deezerAlbumMetadata(album: String, artist: String): CatalogAlbum? = withContext(Dispatchers.IO) {
        val query = "artist:\"${artist.replace("\"", "")}\" album:\"${album.replace("\"", "")}\""
        val searchUrl = "https://api.deezer.com/search/album".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()
        val items = (deezerJson(searchUrl.toString())["data"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()
            .ifEmpty {
                val fallbackUrl = "https://api.deezer.com/search/album".toHttpUrl().newBuilder()
                    .addQueryParameter("q", "$artist $album")
                    .addQueryParameter("limit", "10")
                    .build()
                (deezerJson(fallbackUrl.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            }
        val match = items.firstOrNull { item ->
            val title = item["title"] as? String ?: ""
            val art = (item["artist"] as? Map<*, *>)?.get("name") as? String ?: ""
            Names.releaseKey(title) == Names.releaseKey(album) && Names.comparable(art) == Names.comparable(artist)
        } ?: return@withContext null

        val id = match.longStr("id") ?: return@withContext null
        val detail = runCatching { deezerJson("https://api.deezer.com/album/$id") }.getOrDefault(match)
        val cover = ((detail["cover_xl"] as? String) ?: (match["cover_xl"] as? String)
            ?: (detail["cover_big"] as? String) ?: (match["cover_big"] as? String))
            ?.takeIf { "/album//" !in it }
        val year = (detail["release_date"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 }
        val genre = (((detail["genres"] as? Map<*, *>)?.get("data") as? List<*>)
            ?.firstOrNull() as? Map<*, *>)?.get("name") as? String
        CatalogAlbum(
            id = "dz:$id",
            title = (detail["title"] as? String) ?: album,
            artist = ((detail["artist"] as? Map<*, *>)?.get("name") as? String) ?: artist,
            year = year,
            coverUrl = cover,
            genre = genre,
            trackCount = (detail["nb_tracks"] as? Number)?.toInt() ?: (match["nb_tracks"] as? Number)?.toInt(),
            recordType = (detail["record_type"] as? String) ?: "album",
            source = CatalogPlaylist.SOURCE_DEEZER,
        )
    }

    /** Album cover (1000x1000), release year and genre from Apple Music / iTunes for [album] by [artist]. */
    suspend fun appleAlbumMetadata(album: String, artist: String): CatalogAlbum? = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $album")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "album")
            .addQueryParameter("limit", "10")
            .build()
        val results = (appleJson(url.toString())["results"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()
        val match = results.firstOrNull { item ->
            val title = item["collectionName"] as? String ?: ""
            val art = item["artistName"] as? String ?: ""
            Names.releaseKey(title) == Names.releaseKey(album) && Names.comparable(art) == Names.comparable(artist)
        } ?: return@withContext null

        CatalogAlbum(
            id = "ap:${match.longStr("collectionId") ?: ""}",
            title = (match["collectionName"] as? String) ?: album,
            artist = (match["artistName"] as? String) ?: artist,
            year = (match["releaseDate"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 },
            coverUrl = appleHiResCover(match["artworkUrl100"] as? String),
            genre = (match["primaryGenreName"] as? String)?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) },
            trackCount = (match["trackCount"] as? Number)?.toInt(),
            source = CatalogPlaylist.SOURCE_APPLE,
        )
    }

    /** Primary genre for [artist] from Apple Music / iTunes or Deezer's top albums. */
    suspend fun artistGenres(artist: String, useDeezer: Boolean, useApple: Boolean): List<String> = withContext(Dispatchers.IO) {
        val genres = LinkedHashSet<String>()
        if (useApple) {
            runCatching {
                val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                    .addQueryParameter("term", artist)
                    .addQueryParameter("media", "music")
                    .addQueryParameter("entity", "musicArtist")
                    .addQueryParameter("limit", "5")
                    .build()
                val results = (appleJson(url.toString())["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                val match = results.firstOrNull { Names.comparable(it["artistName"] as? String ?: "") == Names.comparable(artist) }
                val g = (match?.get("primaryGenreName") as? String)?.trim()?.lowercase(Locale.ROOT)
                if (!g.isNullOrBlank() && g != "music") genres += g
            }.onFailure { Log.i(TAG, "Apple artist genre failed for $artist: $it") }
        }
        if (genres.isEmpty() && useDeezer) {
            runCatching {
                val searchUrl = "https://api.deezer.com/search/album".toHttpUrl().newBuilder()
                    .addQueryParameter("q", "artist:\"${artist.replace("\"", "")}\"")
                    .addQueryParameter("limit", "5")
                    .build()
                val albums = (deezerJson(searchUrl.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                val first = albums.firstOrNull {
                    Names.comparable((it["artist"] as? Map<*, *>)?.get("name") as? String ?: "") == Names.comparable(artist)
                }
                val id = first?.longStr("id")
                if (id != null) {
                    val detail = deezerJson("https://api.deezer.com/album/$id")
                    val list = ((detail["genres"] as? Map<*, *>)?.get("data") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                    for (g in list) {
                        val name = (g["name"] as? String)?.trim()?.lowercase(Locale.ROOT)
                        if (!name.isNullOrBlank()) genres += name
                    }
                }
            }.onFailure { Log.i(TAG, "Deezer artist genre failed for $artist: $it") }
        }
        genres.toList()
    }

    /** Looks up a single untagged or partially tagged track on Deezer or Apple Music. */
    suspend fun lookupTrack(title: String, artist: String, album: String?, useDeezer: Boolean, useApple: Boolean): CatalogTrack? =
        withContext(Dispatchers.IO) {
            if (useDeezer) {
                runCatching { deezerTrackLookup(title, artist, album) }.getOrNull()?.let { return@withContext it }
            }
            if (useApple) {
                runCatching { appleTrackLookup(title, artist, album) }.getOrNull()?.let { return@withContext it }
            }
            null
        }

    private suspend fun deezerTrackLookup(title: String, artist: String, album: String?): CatalogTrack? {
        val q = buildString {
            append("artist:\"").append(artist.replace("\"", "")).append("\" ")
            append("track:\"").append(title.replace("\"", "")).append("\"")
            if (!album.isNullOrBlank()) append(" album:\"").append(album.replace("\"", "")).append("\"")
        }
        val url = "https://api.deezer.com/search/track".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", "10")
            .build()
        val data = (deezerJson(url.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val match = data.firstOrNull { item ->
            val t = item["title"] as? String ?: ""
            val a = (item["artist"] as? Map<*, *>)?.get("name") as? String ?: ""
            Names.comparable(t) == Names.comparable(title) && Names.comparable(a) == Names.comparable(artist)
        } ?: return null
        val albMap = match["album"] as? Map<*, *>
        val albTitle = albMap?.get("title") as? String ?: album ?: ""
        val albId = albMap?.longStr("id")
        val albMeta = if (albId != null) runCatching { deezerAlbumMetadata(albTitle, artist) }.getOrNull() else null
        return CatalogTrack(
            id = "dz:${match.longStr("id") ?: ""}",
            title = (match["title"] as? String) ?: title,
            artist = ((match["artist"] as? Map<*, *>)?.get("name") as? String) ?: artist,
            album = albTitle,
            albumArtist = albMeta?.artist ?: artist,
            trackNumber = null,
            discNumber = null,
            durationMs = ((match["duration"] as? Number)?.toLong() ?: 0L) * 1000L,
            year = albMeta?.year,
            genre = albMeta?.genre,
            coverUrl = albMeta?.coverUrl ?: (albMap?.get("cover_xl") as? String) ?: (albMap?.get("cover_big") as? String),
            albumId = albId?.let { "dz:$it" },
            source = CatalogPlaylist.SOURCE_DEEZER,
        )
    }

    private suspend fun appleTrackLookup(title: String, artist: String, album: String?): CatalogTrack? {
        val term = listOfNotNull(artist, title, album).joinToString(" ")
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val results = (appleJson(url.toString())["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val match = results.firstOrNull { item ->
            val t = item["trackName"] as? String ?: ""
            val a = item["artistName"] as? String ?: ""
            Names.comparable(t) == Names.comparable(title) && Names.comparable(a) == Names.comparable(artist)
        } ?: return null
        return CatalogTrack(
            id = "ap:${match.longStr("trackId") ?: ""}",
            title = (match["trackName"] as? String) ?: title,
            artist = (match["artistName"] as? String) ?: artist,
            album = (match["collectionName"] as? String) ?: album ?: "",
            albumArtist = (match["collectionArtistName"] as? String) ?: (match["artistName"] as? String),
            trackNumber = (match["trackNumber"] as? Number)?.toInt(),
            discNumber = (match["discNumber"] as? Number)?.toInt(),
            durationMs = (match["trackTimeMillis"] as? Number)?.toLong() ?: 0L,
            year = (match["releaseDate"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 },
            genre = (match["primaryGenreName"] as? String)?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) },
            coverUrl = appleHiResCover(match["artworkUrl100"] as? String),
            albumId = match.longStr("collectionId")?.let { "ap:$it" },
            source = CatalogPlaylist.SOURCE_APPLE,
        )
    }

    // ---- Low-level rate-limited HTTP helpers ----

    private suspend fun deezerJson(url: String): Map<*, *> = deezerMutex.withLock {
        val wait = lastDeezerCall + DEEZER_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            getJson(url)
        } finally {
            lastDeezerCall = System.currentTimeMillis()
        }
    }

    private suspend fun appleJson(url: String): Map<*, *> = appleMutex.withLock {
        val wait = lastAppleCall + APPLE_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            getJson(url)
        } finally {
            lastAppleCall = System.currentTimeMillis()
        }
    }

    private fun getJson(url: String): Map<*, *> =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} for $url")
            moshi.adapter(Map::class.java).fromJson(response.body.string()) ?: emptyMap<Any, Any>()
        }

    private fun appleHiResCover(url: String?): String? =
        url?.replace("100x100bb", "1000x1000bb")?.replace("60x60bb", "1000x1000bb")

    private fun Map<*, *>.longStr(key: String): String? = when (val v = this[key]) {
        is Number -> v.toLong().toString()
        is String -> v.takeIf { it.isNotBlank() }
        else -> null
    }

    companion object {
        private const val TAG = "CatalogClient"
        /** Deezer allows 50 requests / 5 s (100 ms min); 120 ms leaves comfortable headroom. */
        private const val DEEZER_INTERVAL_MS = 120L
        /** Apple public iTunes search allows ~20–30 requests / min; 1.2 s spaces sequential calls safely. */
        private const val APPLE_INTERVAL_MS = 1_200L
    }
}
