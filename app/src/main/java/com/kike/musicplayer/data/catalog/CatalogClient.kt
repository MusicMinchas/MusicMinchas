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
    val mosaicCovers: List<String> = emptyList(),
) {
    companion object {
        const val SOURCE_DEEZER = "deezer"
        const val SOURCE_APPLE = "apple"
    }
}

fun isDefaultCatalogCover(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    return url.contains("assets/meta") || url.contains("apple-music-") || url.contains("/playlist//")
}

/**
 * Normalizes Apple Music mzstatic image URLs to 600x600 square full-bleed artwork,
 * removing social card letterboxing (such as 1200x630 or wallpaper preview wp) and replacing dimension templates ({w}x{h}).
 */
fun normalizeArtworkUrl(uri: Any?): Any? {
    val str = uri as? String ?: (uri as? android.net.Uri)?.toString() ?: return uri
    val unescaped = if (str.contains("&amp;")) str.replace("&amp;", "&") else str
    if (unescaped.contains("mzstatic.com")) {
        var res = unescaped.replace("{w}x{h}", "600x600").replace("{f}", "jpg")
        res = Regex("""/\d+x\d+wp(?:-\d+)?\.([a-zA-Z0-9]+)""").replace(res) { match ->
            "/600x600cc.${match.groupValues[1]}"
        }
        val regex = Regex("""/(\d+)x(\d+)([A-Za-z0-9._-]*)""")
        val result = regex.replace(res) { match ->
            val w = match.groupValues[1]
            val h = match.groupValues[2]
            val rest = match.groupValues[3]
            if (w != h) "/600x600$rest" else match.value
        }
        return if (uri is android.net.Uri) android.net.Uri.parse(result) else result
    }
    return if (uri is android.net.Uri) android.net.Uri.parse(unescaped) else unescaped
}

data class ResolvedPlaylist(
    val playlist: CatalogPlaylist,
    val tracks: List<CatalogTrack>,
)

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
data class DeezerHomeSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val playlists: List<CatalogPlaylist>,
)

data class DeezerHomeContent(
    val sections: List<DeezerHomeSection>,
    val fetchedAt: Long = System.currentTimeMillis(),
)

class CatalogClient(
    private val http: OkHttpClient,
    private val moshi: Moshi,
    private val cacheDir: java.io.File? = null,
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

    /**
     * Slices of public Deezer charts and curated playlists to enrich the Home tab.
     * Caches result to disk for [HOME_CACHE_TTL_MS] (24 hours) for instant zero-latency startup.
     * Falls back to expired cache on network errors.
     */
    suspend fun fetchDeezerHomeContent(forceRefresh: Boolean = false): DeezerHomeContent? = withContext(Dispatchers.IO) {
        val cacheFile = cacheDir?.resolve("deezer_home_content.json")
        val now = System.currentTimeMillis()

        if (!forceRefresh && cacheFile != null && cacheFile.exists()) {
            runCatching {
                val json = cacheFile.readText()
                val cached = moshi.adapter(DeezerHomeContent::class.java).fromJson(json)
                if (cached != null && (now - cached.fetchedAt) < HOME_CACHE_TTL_MS && cached.sections.isNotEmpty()) {
                    return@withContext cached
                }
            }.onFailure { Log.w(TAG, "Failed reading cached Deezer home content", it) }
        }

        runCatching {
            val sectionsConfig = listOf(
                Triple("trending", "Trending on Deezer", "https://api.deezer.com/chart/0/playlists?limit=10") to "Most popular playlists worldwide",
                Triple("moods", "Chill & Focus", "https://api.deezer.com/chart/98/playlists?limit=10") to "Peaceful piano, ambient & acoustic",
                Triple("rock", "Rock & Alternative", "https://api.deezer.com/chart/152/playlists?limit=10") to "Guitar anthems, essentials & classic hits",
                Triple("acoustic", "Folk & Acoustic", "https://api.deezer.com/chart/466/playlists?limit=10") to "Cozy acoustic, coffeehouse & folk",
            )

            val sections = sectionsConfig.mapNotNull { (cfg, subtitle) ->
                val (id, title, url) = cfg
                val raw = deezerJson(url)
                val data = (raw["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                val playlists = data.mapNotNull { item ->
                    val plId = item.longStr("id") ?: return@mapNotNull null
                    val plTitle = (item["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val count = (item["nb_tracks"] as? Number)?.toInt() ?: 0
                    val creator = (item["user"] as? Map<*, *>)?.get("name") as? String
                    val cover = ((item["picture_xl"] as? String)
                        ?: (item["picture_big"] as? String)
                        ?: (item["picture_medium"] as? String))?.takeIf { "/playlist//" !in it }
                    CatalogPlaylist(
                        id = "dz:$plId",
                        title = plTitle,
                        creator = creator,
                        trackCount = count,
                        coverUrl = cover,
                        source = CatalogPlaylist.SOURCE_DEEZER,
                    )
                }
                if (playlists.isNotEmpty()) {
                    DeezerHomeSection(id = id, title = title, subtitle = subtitle, playlists = playlists)
                } else null
            }

            if (sections.isNotEmpty()) {
                val content = DeezerHomeContent(sections = sections, fetchedAt = now)
                if (cacheFile != null) {
                    runCatching {
                        val json = moshi.adapter(DeezerHomeContent::class.java).toJson(content)
                        cacheFile.writeText(json)
                    }.onFailure { Log.w(TAG, "Failed writing Deezer home content cache", it) }
                }
                content
            } else null
        }.onFailure { Log.w(TAG, "fetchDeezerHomeContent failed: $it") }
            .getOrNull() ?: run {
                // Network failed: fallback to existing cached content if available
                if (cacheFile != null && cacheFile.exists()) {
                    runCatching {
                        moshi.adapter(DeezerHomeContent::class.java).fromJson(cacheFile.readText())
                    }.getOrNull()
                } else null
            }
    }

    /** Tracks inside a public catalog playlist (Deezer `dz:...` or Apple Music `ap:...`). */
    suspend fun playlistTracks(playlist: CatalogPlaylist): List<CatalogTrack> = withContext(Dispatchers.IO) {
        if (playlist.source == CatalogPlaylist.SOURCE_APPLE || playlist.id.startsWith("ap:")) {
            val rawId = playlist.id.removePrefix("ap:")
            fetchApplePlaylistTracks(rawId)
        } else {
            runCatching {
                val rawId = playlist.id.removePrefix("dz:")
                val url = "https://api.deezer.com/playlist/$rawId/tracks?limit=200"
                val data = (deezerJson(url)["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                data.mapNotNull { parseDeezerTrack(it) }
            }.onFailure { Log.w(TAG, "playlistTracks failed for ${playlist.id}: $it") }.getOrDefault(emptyList())
        }
    }

    /** Resolves a playlist from an Apple Music or Deezer URL, returning its metadata and tracklist. */
    suspend fun resolvePlaylistUrl(rawUrl: String): ResolvedPlaylist = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val normalized = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val finalUrl = when {
            normalized.contains("deezer.com", ignoreCase = true) ||
            normalized.contains("music.apple.com", ignoreCase = true) ||
            normalized.contains("itunes.apple.com", ignoreCase = true) -> normalized
            else -> resolveRedirects(normalized)
        }

        when {
            finalUrl.contains("deezer.com", ignoreCase = true) || finalUrl.contains("deezer.page.link", ignoreCase = true) -> {
                resolveDeezerPlaylist(finalUrl)
            }
            finalUrl.contains("music.apple.com", ignoreCase = true) || finalUrl.contains("itunes.apple.com", ignoreCase = true) -> {
                resolveApplePlaylist(finalUrl)
            }
            else -> throw IllegalArgumentException("Unsupported playlist URL. Please provide an Apple Music or Deezer playlist link.")
        }
    }

    private fun resolveRedirects(url: String): String = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { response ->
            response.request.url.toString()
        }
    }.getOrDefault(url)

    private suspend fun resolveDeezerPlaylist(url: String): ResolvedPlaylist {
        val playlistIdRegex = Regex("""(?:/playlist/|/playlist\?id=)(\d+)""")
        val id = playlistIdRegex.find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Couldn't find Deezer playlist ID in URL.")

        val json = deezerJson("https://api.deezer.com/playlist/$id")
        if (json.containsKey("error")) {
            val errMsg = (json["error"] as? Map<*, *>)?.get("message") as? String ?: "Deezer API error"
            throw java.io.IOException("Deezer playlist error: $errMsg")
        }

        val title = (json["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Deezer Playlist"
        val creator = (json["creator"] as? Map<*, *>)?.get("name") as? String
        val nbTracks = (json["nb_tracks"] as? Number)?.toInt() ?: 0
        val coverUrl = ((json["picture_xl"] as? String)
            ?: (json["picture_big"] as? String)
            ?: (json["picture_medium"] as? String))?.takeIf { !isDefaultCatalogCover(it) }

        val tracksData = (json["tracks"] as? Map<*, *>)?.get("data") as? List<*>
        val tracks = tracksData?.mapNotNull { (it as? Map<*, *>)?.let { map -> parseDeezerTrack(map) } }.orEmpty()

        val mosaic = if (coverUrl.isNullOrBlank()) {
            tracks.mapNotNull { it.coverUrl }.filter { it.isNotBlank() }.distinct().take(4)
        } else emptyList()

        val playlist = CatalogPlaylist(
            id = "dz:$id",
            title = title,
            creator = creator,
            trackCount = if (nbTracks > 0) nbTracks else tracks.size,
            coverUrl = coverUrl,
            source = CatalogPlaylist.SOURCE_DEEZER,
            mosaicCovers = mosaic,
        )
        return ResolvedPlaylist(playlist, tracks)
    }

    private suspend fun resolveApplePlaylist(url: String): ResolvedPlaylist {
        val playlistIdRegex = Regex("""(pl\.[a-zA-Z0-9_-]+)""")
        val id = playlistIdRegex.find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Couldn't find Apple Music playlist ID in URL.")

        val targetUrl = if (url.contains("music.apple.com")) url else "https://music.apple.com/us/playlist/$id"
        val req = Request.Builder().url(targetUrl).header("User-Agent", USER_AGENT).build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} loading Apple Music playlist")
            resp.body.string()
        }

        var title: String? = null
        val nameRegex = Regex(""""@type"\s*:\s*"MusicPlaylist"[^}]*?"name"\s*:\s*"([^"]+)"""")
        nameRegex.find(html)?.groupValues?.get(1)?.let {
            title = unescapeJsonString(it)
        }
        if (title.isNullOrBlank()) {
            val ogTitleRegex = Regex("""property="og:title"\s+content="([^"]+)"""")
            ogTitleRegex.find(html)?.groupValues?.get(1)?.let {
                title = it.replace(" on Apple Music", "").replace(" - Playlist - Apple Music", "").trim()
            }
        }
        if (title.isNullOrBlank()) title = "Apple Music Playlist"

        var creator: String? = null
        val creatorRegex = Regex(""""author"\s*:\s*\{[^}]*?"name"\s*:\s*"([^"]+)"""")
        creatorRegex.find(html)?.groupValues?.get(1)?.let {
            creator = unescapeJsonString(it)
        }
        if (creator.isNullOrBlank()) {
            val metaCreatorRegex = Regex("""(?:name|property)="(?:apple:creator|music:creator)"\s+content="([^"]+)"""")
            metaCreatorRegex.find(html)?.groupValues?.get(1)?.let {
                creator = it.trim()
            }
        }
        if (creator.isNullOrBlank()) {
            val metaCreatorRegex2 = Regex("""content="([^"]+)"\s+(?:name|property)="(?:apple:creator|music:creator)"""")
            metaCreatorRegex2.find(html)?.groupValues?.get(1)?.let {
                creator = it.trim()
            }
        }

        val ogImageRegex = Regex("""property="og:image"\s+content="([^"]+)"""")
        val rawCoverUrl = ogImageRegex.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { !isDefaultCatalogCover(it) }
        val coverUrl = rawCoverUrl?.let { normalizeArtworkUrl(it) as? String ?: it }

        val tracks = parseApplePlaylistTracksFromHtml(html)

        val mosaic = if (coverUrl.isNullOrBlank()) {
            tracks.mapNotNull { it.coverUrl }.filter { it.isNotBlank() }.distinct().take(4)
        } else emptyList()

        val playlist = CatalogPlaylist(
            id = "ap:$id",
            title = title,
            creator = creator,
            trackCount = tracks.size,
            coverUrl = coverUrl,
            source = CatalogPlaylist.SOURCE_APPLE,
            mosaicCovers = mosaic,
        )
        return ResolvedPlaylist(playlist, tracks)
    }

    private suspend fun fetchApplePlaylistTracks(rawId: String): List<CatalogTrack> = runCatching {
        val targetUrl = "https://music.apple.com/us/playlist/$rawId"
        val req = Request.Builder().url(targetUrl).header("User-Agent", USER_AGENT).build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $targetUrl")
            resp.body.string()
        }
        parseApplePlaylistTracksFromHtml(html)
    }.onFailure { Log.w(TAG, "fetchApplePlaylistTracks failed for $rawId: $it") }.getOrDefault(emptyList())

    private fun parseApplePlaylistTracksFromHtml(html: String): List<CatalogTrack> {
        val tracks = mutableListOf<CatalogTrack>()
        val seenIds = mutableSetOf<String>()

        val scriptRegex = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        for (match in scriptRegex.findAll(html)) {
            val content = match.groupValues[1].trim()
            if (content.contains("artistName") && content.contains("title")) {
                val parsed = runCatching {
                    moshi.adapter(Map::class.java).fromJson(content)
                }.getOrNull()
                if (parsed != null) {
                    extractAppleTracksFromMap(parsed, seenIds, tracks)
                    if (tracks.isNotEmpty()) break
                }
            }
        }
        return tracks
    }

    private fun extractAppleTracksFromMap(obj: Any?, seenIds: MutableSet<String>, out: MutableList<CatalogTrack>) {
        when (obj) {
            is Map<*, *> -> {
                val title = (obj["title"] as? String)?.takeIf { it.isNotBlank() }
                val artistName = (obj["artistName"] as? String)?.takeIf { it.isNotBlank() }
                if (title != null && artistName != null) {
                    val cd = obj["contentDescriptor"] as? Map<*, *>
                    val idents = cd?.get("identifiers") as? Map<*, *>
                    val trackId = (idents?.get("storeAdamID") as? String)
                        ?: (idents?.get("storeAdamID") as? Number)?.toLong()?.toString()
                    if (trackId != null && seenIds.add(trackId)) {
                        val durationMs = (obj["duration"] as? Number)?.toLong() ?: 0L
                        val tLinks = obj["tertiaryLinks"] as? List<*>
                        val firstLink = tLinks?.firstOrNull() as? Map<*, *>
                        val albumTitle = (firstLink?.get("title") as? String) ?: ""
                        val segue = firstLink?.get("segue") as? Map<*, *>
                        val dest = segue?.get("destination") as? Map<*, *>
                        val albumCd = dest?.get("contentDescriptor") as? Map<*, *>
                        val albumIdents = albumCd?.get("identifiers") as? Map<*, *>
                        val albumId = (albumIdents?.get("storeAdamID") as? String)
                            ?: (albumIdents?.get("storeAdamID") as? Number)?.toLong()?.toString()
                        val artwork = obj["artwork"] as? Map<*, *>
                        val dict = artwork?.get("dictionary") as? Map<*, *>
                        val artUrl = dict?.get("url") as? String
                        val coverUrl = artUrl?.replace("{w}x{h}", "1000x1000")?.replace("{f}", "jpg")
                            ?: (obj["thumbnailUrl"] as? String)

                        out.add(
                            CatalogTrack(
                                id = "ap:$trackId",
                                title = title,
                                artist = artistName,
                                album = albumTitle,
                                albumArtist = artistName,
                                trackNumber = out.size + 1,
                                discNumber = null,
                                durationMs = durationMs,
                                year = null,
                                genre = null,
                                coverUrl = coverUrl,
                                albumId = albumId?.let { "ap:$it" },
                                source = CatalogPlaylist.SOURCE_APPLE,
                            )
                        )
                    }
                }
                for (v in obj.values) {
                    extractAppleTracksFromMap(v, seenIds, out)
                }
            }
            is List<*> -> {
                for (item in obj) {
                    extractAppleTracksFromMap(item, seenIds, out)
                }
            }
        }
    }

    private fun unescapeJsonString(s: String): String = runCatching {
        moshi.adapter(String::class.java).fromJson("\"" + s.replace("\"", "\\\"") + "\"") ?: s
    }.getOrDefault(s)

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
        private const val HOME_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
