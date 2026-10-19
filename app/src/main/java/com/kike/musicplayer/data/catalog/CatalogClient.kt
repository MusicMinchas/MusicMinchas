package com.kike.musicplayer.data.catalog

import android.util.Log
import com.kike.musicplayer.data.discover.Names
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

data class CatalogSearchResults(
    val artists: List<CatalogArtist> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val songs: List<CatalogTrack> = emptyList(),
    val playlists: List<CatalogPlaylist> = emptyList(),
    val loading: Boolean = false,
)

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

    // ---- Catalog Search & Detail Lookups (Step 2) ----

    /**
     * Searches Deezer and Apple Music in parallel (a single combined entity request on Apple Music
     * to respect its rate limit) and merges duplicate artists, albums, and tracks.
     */
    suspend fun searchCatalog(query: String, includePlaylists: Boolean = false): CatalogSearchResults =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.length < 2) return@withContext CatalogSearchResults()
            coroutineScope {
                val dzArtistsDef = async { runCatching { searchDeezerArtists(trimmed, 8) }.getOrDefault(emptyList()) }
                val dzAlbumsDef = async { runCatching { searchDeezerAlbums(trimmed, 12) }.getOrDefault(emptyList()) }
                val dzTracksDef = async { runCatching { searchDeezerTracks(trimmed, 20) }.getOrDefault(emptyList()) }
                val dzPlaylistsDef = async {
                    if (includePlaylists) runCatching { searchPlaylists(trimmed, 12) }.getOrDefault(emptyList()) else emptyList()
                }
                val appleDef = async { runCatching { searchAppleCombined(trimmed, 25) }.getOrDefault(Triple(emptyList(), emptyList(), emptyList())) }

                val dzArtists = dzArtistsDef.await()
                val dzAlbums = dzAlbumsDef.await()
                val dzTracks = dzTracksDef.await()
                val dzPlaylists = dzPlaylistsDef.await()
                val (apArtists, apAlbums, apTracks) = appleDef.await()

                // Merge artists (Deezer first because it includes 1000x1000 artist photos).
                val mergedArtists = LinkedHashMap<String, CatalogArtist>()
                for (a in dzArtists + apArtists) {
                    val key = Names.comparable(a.name)
                    if (key.isEmpty()) continue
                    val existing = mergedArtists[key]
                    if (existing == null) {
                        mergedArtists[key] = a
                    } else if (existing.pictureUrl == null && a.pictureUrl != null) {
                        mergedArtists[key] = existing.copy(pictureUrl = a.pictureUrl)
                    }
                }

                // Merge albums (combine Deezer & Apple Music so year/genre from Apple enrich Deezer's row).
                val mergedAlbums = LinkedHashMap<String, CatalogAlbum>()
                for (al in dzAlbums + apAlbums) {
                    val key = Names.comparable(al.artist) + "\u0001" + Names.releaseKey(al.title)
                    val existing = mergedAlbums[key]
                    if (existing == null) {
                        mergedAlbums[key] = al
                    } else {
                        mergedAlbums[key] = existing.copy(
                            year = existing.year ?: al.year,
                            genre = existing.genre ?: al.genre,
                            coverUrl = existing.coverUrl ?: al.coverUrl,
                            trackCount = existing.trackCount ?: al.trackCount,
                        )
                    }
                }

                // Merge tracks (combine Deezer & Apple Music metadata).
                val mergedTracks = LinkedHashMap<String, CatalogTrack>()
                for (t in dzTracks + apTracks) {
                    val key = Names.comparable(t.artist) + "\u0001" + Names.comparable(t.title)
                    val existing = mergedTracks[key]
                    if (existing == null) {
                        mergedTracks[key] = t
                    } else {
                        mergedTracks[key] = existing.copy(
                            year = existing.year ?: t.year,
                            genre = existing.genre ?: t.genre,
                            trackNumber = existing.trackNumber ?: t.trackNumber,
                            discNumber = existing.discNumber ?: t.discNumber,
                            albumArtist = existing.albumArtist ?: t.albumArtist,
                            coverUrl = existing.coverUrl ?: t.coverUrl,
                        )
                    }
                }

                CatalogSearchResults(
                    artists = mergedArtists.values.take(10),
                    albums = mergedAlbums.values.take(15),
                    songs = mergedTracks.values.take(25),
                    playlists = dzPlaylists,
                )
            }
        }

    /** Full tracklist for a catalog album (`dz:...` or `ap:...`). */
    suspend fun albumTracks(album: CatalogAlbum): List<CatalogTrack> = withContext(Dispatchers.IO) {
        runCatching {
            if (album.id.startsWith("dz:")) {
                val rawId = album.id.removePrefix("dz:")
                val detail = deezerJson("https://api.deezer.com/album/$rawId")
                val year = (detail["release_date"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 } ?: album.year
                val genre = (((detail["genres"] as? Map<*, *>)?.get("data") as? List<*>)
                    ?.firstOrNull() as? Map<*, *>)?.get("name") as? String ?: album.genre
                val cover = ((detail["cover_xl"] as? String) ?: (detail["cover_big"] as? String))
                    ?.takeIf { "/album//" !in it } ?: album.coverUrl
                val tracksData = ((detail["tracks"] as? Map<*, *>)?.get("data") as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }
                    .orEmpty()
                tracksData.mapIndexed { idx, item ->
                    val artistName = ((item["artist"] as? Map<*, *>)?.get("name") as? String) ?: album.artist
                    CatalogTrack(
                        id = "dz:${item.longStr("id") ?: idx}",
                        title = (item["title"] as? String) ?: "",
                        artist = artistName,
                        album = album.title,
                        albumArtist = album.artist,
                        trackNumber = (item["track_position"] as? Number)?.toInt() ?: (idx + 1),
                        discNumber = (item["disk_number"] as? Number)?.toInt() ?: 1,
                        durationMs = ((item["duration"] as? Number)?.toLong() ?: 0L) * 1000L,
                        year = year,
                        genre = genre,
                        coverUrl = cover,
                        albumId = album.id,
                        source = CatalogPlaylist.SOURCE_DEEZER,
                    )
                }.filter { it.title.isNotBlank() }
            } else if (album.id.startsWith("ap:")) {
                val rawId = album.id.removePrefix("ap:")
                val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                    .addQueryParameter("id", rawId)
                    .addQueryParameter("entity", "song")
                    .addQueryParameter("limit", "200")
                    .build()
                val results = (appleJson(url.toString())["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                results.filter { it["wrapperType"] == "track" }.mapIndexed { idx, item ->
                    CatalogTrack(
                        id = "ap:${item.longStr("trackId") ?: idx}",
                        title = (item["trackName"] as? String) ?: "",
                        artist = (item["artistName"] as? String) ?: album.artist,
                        album = (item["collectionName"] as? String) ?: album.title,
                        albumArtist = (item["collectionArtistName"] as? String) ?: album.artist,
                        trackNumber = (item["trackNumber"] as? Number)?.toInt() ?: (idx + 1),
                        discNumber = (item["discNumber"] as? Number)?.toInt() ?: 1,
                        durationMs = (item["trackTimeMillis"] as? Number)?.toLong() ?: 0L,
                        year = (item["releaseDate"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 } ?: album.year,
                        genre = (item["primaryGenreName"] as? String)?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) } ?: album.genre,
                        coverUrl = appleHiResCover(item["artworkUrl100"] as? String) ?: album.coverUrl,
                        albumId = album.id,
                        source = CatalogPlaylist.SOURCE_APPLE,
                    )
                }.filter { it.title.isNotBlank() }
            } else {
                emptyList()
            }
        }.onFailure { Log.w(TAG, "albumTracks failed for ${album.id}: $it") }.getOrDefault(emptyList())
    }

    /** Top songs and discography albums for a catalog artist (`dz:...` or `ap:...`). */
    suspend fun artistOverview(artist: CatalogArtist): Pair<List<CatalogTrack>, List<CatalogAlbum>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dzId = if (artist.id.startsWith("dz:")) {
                    artist.id.removePrefix("dz:")
                } else {
                    searchDeezerArtists(artist.name, 5)
                        .firstOrNull { Names.comparable(it.name) == Names.comparable(artist.name) }
                        ?.id?.removePrefix("dz:")
                }
                if (dzId != null) {
                    val topUrl = "https://api.deezer.com/artist/$dzId/top?limit=10"
                    val topData = (deezerJson(topUrl)["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                    val topTracks = topData.mapNotNull { parseDeezerTrack(it) }

                    val albumsUrl = "https://api.deezer.com/artist/$dzId/albums?limit=60"
                    val albumsData = (deezerJson(albumsUrl)["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                    val albums = albumsData
                        .mapNotNull { item ->
                            val id = item.longStr("id") ?: return@mapNotNull null
                            val title = (item["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val recordType = (item["record_type"] as? String) ?: "album"
                            if (recordType == "single") return@mapNotNull null
                            val cover = ((item["cover_xl"] as? String) ?: (item["cover_big"] as? String))?.takeIf { "/album//" !in it }
                            val year = (item["release_date"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 }
                            CatalogAlbum(
                                id = "dz:$id",
                                title = title,
                                artist = artist.name,
                                year = year,
                                coverUrl = cover,
                                genre = null,
                                trackCount = (item["nb_tracks"] as? Number)?.toInt(),
                                recordType = recordType,
                                source = CatalogPlaylist.SOURCE_DEEZER,
                            )
                        }
                        .distinctBy { Names.releaseKey(it.title) }
                        .sortedByDescending { it.year ?: 0 }
                    return@runCatching topTracks to albums
                }
                // Apple Music fallback when artist isn't on Deezer
                val rawApId = artist.id.removePrefix("ap:")
                val lookupUrl = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                    .addQueryParameter("id", rawApId)
                    .addQueryParameter("entity", "album")
                    .addQueryParameter("limit", "50")
                    .build()
                val results = (appleJson(lookupUrl.toString())["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                val albums = results.filter { it["wrapperType"] == "collection" }
                    .mapNotNull { parseAppleAlbum(it) }
                    .distinctBy { Names.releaseKey(it.title) }
                    .sortedByDescending { it.year ?: 0 }
                val songs = searchAppleCombined(artist.name, 15).third
                    .filter { Names.comparable(it.artist) == Names.comparable(artist.name) }
                    .take(10)
                songs to albums
            }.onFailure { Log.w(TAG, "artistOverview failed for ${artist.name}: $it") }
                .getOrDefault(emptyList<CatalogTrack>() to emptyList<CatalogAlbum>())
        }

    /** Public playlists on Deezer matching [query]. */
    suspend fun searchPlaylists(query: String, limit: Int = 15): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        val url = "https://api.deezer.com/search/playlist".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val data = (deezerJson(url.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        data.mapNotNull { item ->
            val id = item.longStr("id") ?: return@mapNotNull null
            val title = (item["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val count = (item["nb_tracks"] as? Number)?.toInt() ?: 0
            if (count <= 0) return@mapNotNull null
            val creator = (item["user"] as? Map<*, *>)?.get("name") as? String
            val cover = ((item["picture_xl"] as? String) ?: (item["picture_big"] as? String) ?: (item["picture_medium"] as? String))
                ?.takeIf { "/playlist//" !in it }
            CatalogPlaylist(
                id = "dz:$id",
                title = title,
                creator = creator,
                trackCount = count,
                coverUrl = cover,
                source = CatalogPlaylist.SOURCE_DEEZER,
            )
        }
    }

    /** Tracks inside a public Deezer playlist (`dz:...`). */
    suspend fun playlistTracks(playlist: CatalogPlaylist): List<CatalogTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val rawId = playlist.id.removePrefix("dz:")
            val url = "https://api.deezer.com/playlist/$rawId/tracks?limit=200"
            val data = (deezerJson(url)["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            data.mapNotNull { parseDeezerTrack(it) }
        }.onFailure { Log.w(TAG, "playlistTracks failed for ${playlist.id}: $it") }.getOrDefault(emptyList())
    }

    /** Studio & live album discography for [artistName] from Deezer and Apple Music. */
    suspend fun artistDiscography(artistName: String): List<CatalogAlbum> = withContext(Dispatchers.IO) {
        runCatching {
            val dzArtist = searchDeezerArtists(artistName, 5)
                .firstOrNull { Names.comparable(it.name) == Names.comparable(artistName) }
            if (dzArtist != null) {
                artistOverview(dzArtist).second
            } else {
                val (_, apAlbums, _) = searchAppleCombined(artistName, 25)
                apAlbums.filter { Names.comparable(it.artist) == Names.comparable(artistName) }
                    .distinctBy { Names.releaseKey(it.title) }
                    .sortedByDescending { it.year ?: 0 }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun searchDeezerArtists(query: String, limit: Int): List<CatalogArtist> {
        val url = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val data = (deezerJson(url.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        return data.mapNotNull { item ->
            val id = item.longStr("id") ?: return@mapNotNull null
            val name = (item["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pic = ((item["picture_xl"] as? String) ?: (item["picture_big"] as? String))?.takeIf { "/artist//" !in it }
            CatalogArtist(
                id = "dz:$id",
                name = name,
                pictureUrl = pic,
                albumCount = (item["nb_album"] as? Number)?.toInt(),
                source = CatalogPlaylist.SOURCE_DEEZER,
            )
        }
    }

    private suspend fun searchDeezerAlbums(query: String, limit: Int): List<CatalogAlbum> {
        val url = "https://api.deezer.com/search/album".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val data = (deezerJson(url.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        return data.mapNotNull { item ->
            val id = item.longStr("id") ?: return@mapNotNull null
            val title = (item["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = ((item["artist"] as? Map<*, *>)?.get("name") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = ((item["cover_xl"] as? String) ?: (item["cover_big"] as? String))?.takeIf { "/album//" !in it }
            CatalogAlbum(
                id = "dz:$id",
                title = title,
                artist = artist,
                year = null,
                coverUrl = cover,
                genre = null,
                trackCount = (item["nb_tracks"] as? Number)?.toInt(),
                recordType = (item["record_type"] as? String) ?: "album",
                source = CatalogPlaylist.SOURCE_DEEZER,
            )
        }
    }

    private suspend fun searchDeezerTracks(query: String, limit: Int): List<CatalogTrack> {
        val url = "https://api.deezer.com/search/track".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val data = (deezerJson(url.toString())["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        return data.mapNotNull { parseDeezerTrack(it) }
    }

    private fun parseDeezerTrack(item: Map<*, *>): CatalogTrack? {
        val id = item.longStr("id") ?: return null
        val title = (item["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val artist = ((item["artist"] as? Map<*, *>)?.get("name") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val albMap = item["album"] as? Map<*, *>
        val albTitle = (albMap?.get("title") as? String) ?: ""
        val albId = albMap?.longStr("id")
        val cover = ((albMap?.get("cover_xl") as? String) ?: (albMap?.get("cover_big") as? String))?.takeIf { "/album//" !in it }
        return CatalogTrack(
            id = "dz:$id",
            title = title,
            artist = artist,
            album = albTitle,
            albumArtist = artist,
            trackNumber = (item["track_position"] as? Number)?.toInt(),
            discNumber = (item["disk_number"] as? Number)?.toInt(),
            durationMs = ((item["duration"] as? Number)?.toLong() ?: 0L) * 1000L,
            year = null,
            genre = null,
            coverUrl = cover,
            albumId = albId?.let { "dz:$it" },
            source = CatalogPlaylist.SOURCE_DEEZER,
        )
    }

    private suspend fun searchAppleCombined(
        query: String,
        limit: Int,
    ): Triple<List<CatalogArtist>, List<CatalogAlbum>, List<CatalogTrack>> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "musicArtist,album,song")
            .addQueryParameter("limit", limit.toString())
            .build()
        val results = (appleJson(url.toString())["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val artists = mutableListOf<CatalogArtist>()
        val albums = mutableListOf<CatalogAlbum>()
        val tracks = mutableListOf<CatalogTrack>()
        for (item in results) {
            when (item["wrapperType"] as? String) {
                "artist" -> {
                    val id = item.longStr("artistId") ?: continue
                    val name = (item["artistName"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                    artists += CatalogArtist(
                        id = "ap:$id",
                        name = name,
                        pictureUrl = null,
                        source = CatalogPlaylist.SOURCE_APPLE,
                    )
                }
                "collection" -> parseAppleAlbum(item)?.let { albums += it }
                "track" -> {
                    val id = item.longStr("trackId") ?: continue
                    val title = (item["trackName"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                    val artist = (item["artistName"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                    tracks += CatalogTrack(
                        id = "ap:$id",
                        title = title,
                        artist = artist,
                        album = (item["collectionName"] as? String) ?: "",
                        albumArtist = (item["collectionArtistName"] as? String) ?: artist,
                        trackNumber = (item["trackNumber"] as? Number)?.toInt(),
                        discNumber = (item["discNumber"] as? Number)?.toInt(),
                        durationMs = (item["trackTimeMillis"] as? Number)?.toLong() ?: 0L,
                        year = (item["releaseDate"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 },
                        genre = (item["primaryGenreName"] as? String)?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) },
                        coverUrl = appleHiResCover(item["artworkUrl100"] as? String),
                        albumId = item.longStr("collectionId")?.let { "ap:$it" },
                        source = CatalogPlaylist.SOURCE_APPLE,
                    )
                }
            }
        }
        return Triple(artists, albums, tracks)
    }

    private fun parseAppleAlbum(item: Map<*, *>): CatalogAlbum? {
        val id = item.longStr("collectionId") ?: return null
        val title = (item["collectionName"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val artist = (item["artistName"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        if (title.endsWith(" - Single", ignoreCase = true)) return null
        return CatalogAlbum(
            id = "ap:$id",
            title = title,
            artist = artist,
            year = (item["releaseDate"] as? String)?.take(4)?.toIntOrNull()?.takeIf { it > 1900 },
            coverUrl = appleHiResCover(item["artworkUrl100"] as? String),
            genre = (item["primaryGenreName"] as? String)?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) },
            trackCount = (item["trackCount"] as? Number)?.toInt(),
            source = CatalogPlaylist.SOURCE_APPLE,
        )
    }

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
