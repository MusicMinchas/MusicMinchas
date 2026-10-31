package com.kike.musicplayer.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kike.musicplayer.container
import com.kike.musicplayer.data.DriveSyncMode
import com.kike.musicplayer.Bench
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.data.history.HistoricalPlayImporter
import com.kike.musicplayer.data.history.ImportStats
import kotlinx.coroutines.flow.update
import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.recommend.MixCandidates
import com.kike.musicplayer.data.lastfm.TrackMatcher
import com.kike.musicplayer.recommend.SavedMix
import com.kike.musicplayer.recommend.SavedMixSet
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import com.kike.musicplayer.data.SearchText
import com.kike.musicplayer.data.SongSearch
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.kike.musicplayer.data.db.AlbumIndex
import com.kike.musicplayer.data.db.ArtistIndex
import com.kike.musicplayer.data.db.DiscoverItem
import com.kike.musicplayer.data.db.FirstChar
import com.kike.musicplayer.data.db.GenreIndex
import com.kike.musicplayer.data.db.TrackPlays
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.drive.DriveFolder
import com.kike.musicplayer.recommend.ListeningStats
import com.kike.musicplayer.recommend.Mix
import com.kike.musicplayer.recommend.Ranked
import com.kike.musicplayer.recommend.Recommender
import com.kike.musicplayer.recommend.effectiveGenreKey
import com.kike.musicplayer.recommend.Replay
import com.kike.musicplayer.recommend.UNKNOWN_GENRE
import com.kike.musicplayer.recommend.artistTagMap
import com.kike.musicplayer.recommend.ReplayPeriod
import com.kike.musicplayer.recommend.mostPlayed
import com.kike.musicplayer.recommend.heavyRotation
import com.kike.musicplayer.recommend.heavyRotationCandidateIds
import com.kike.musicplayer.recommend.replay
import com.kike.musicplayer.playback.albumTrackOrder
import com.kike.musicplayer.data.nebula.NebulaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val artworkUri: String?,
    val tracks: List<Track>,
)

/** Drive folder browser. An empty [path] is the top level, offering My Drive and Shared with me. */
data class DriveBrowserState(
    val path: List<DriveFolder> = emptyList(),
    val children: List<DriveFolder> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    companion object {
        const val MY_DRIVE_ID = "root"
        const val SHARED_ID = "sharedWithMe"
        val TOP_LEVEL = listOf(DriveFolder(MY_DRIVE_ID, "My Drive"), DriveFolder(SHARED_ID, "Shared with me"))
    }
}

data class SearchResults(
    val artists: List<ArtistIndex> = emptyList(),
    val albums: List<AlbumIndex> = emptyList(),
    val songs: List<Track> = emptyList(),
)

/** An A–Z bar entry: [label] ("#", "A"…) and the list position of its first entry. */
data class Jump(val label: String, val position: Int)

/**
 * A–Z bar entries from the first characters of a sorted list (lower case, no accents): A to Z
 * by themselves, digits and symbols before them as "#", everything after Z (other alphabets,
 * which sort there) as "…"; each label at its first position.
 */
fun jumps(firsts: List<FirstChar>): List<Jump> {
    val out = ArrayList<Jump>()
    var position = 0
    for (f in firsts) {
        val c = f.ch.firstOrNull()
        val label = when {
            c == null || c < 'a' -> "#"
            c <= 'z' -> c.uppercase()
            else -> "…"
        }
        if (out.none { it.label == label }) out += Jump(label, position)
        position += f.count
    }
    return out
}


/** The automatic playlists, built from the play history. */
data class Playlists(
    val recentTracks: List<Track> = emptyList(),
    val recentAlbums: List<Album> = emptyList(),
    /** Albums of the sources switched on, by when their newest song was added, newest first. */
    val addedAlbums: List<Album> = emptyList(),
    /** Counted plays (half the track listened), most first. */
    val heavyRotation: List<Ranked<Track>> = emptyList(),
    val mostPlayed: List<Ranked<Track>> = emptyList(),
    /** Favourite songs, most recently added first. */
    val favourites: List<Track> = emptyList(),
)

/** A user playlist with its songs (those still in the library), in order. */
data class PlaylistView(val id: Long, val name: String, val tracks: List<Track>, val lastPlayedAt: Long = 0L, val createdAt: Long = 0L)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val repo = container.library
    private val settings = container.settings
    private val driveAuth = container.driveAuth

    /** The sources (Track scopes) that have songs in the library, in menu order. */
    val availableScopes: StateFlow<List<Int>> = container.database.browse().observeSourceCounts()
        .map { counts ->
            val present = counts.filter { it.count > 0 }.mapTo(HashSet()) { Track.scopeOf(it.source) }
            Track.SCOPES.filter { it in present }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Track.SCOPES)

    /** Sources switched off in the library menu. */
    val hiddenScopes: StateFlow<Set<Int>> = settings.hiddenScopes

    /** Switches a source on or off; the last one shown stays on. */
    fun toggleScope(scope: Int) {
        val hidden = settings.hiddenScopes.value
        val next = if (scope in hidden) hidden - scope else hidden + scope
        if (availableScopes.value.any { it !in next }) settings.setHiddenScopes(next)
    }

    /** The sources the library lists: those with songs, minus the switched-off ones (never none). */
    private val viewScopes: StateFlow<List<Int>> = combine(availableScopes, settings.hiddenScopes) { available, hidden ->
        available.filter { it !in hidden }.ifEmpty { available }.ifEmpty { Track.SCOPES }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, Track.SCOPES)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Library search by artist name, album title and song title, in the database's search tables. */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<SearchResults> = _query
        .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .combine(viewScopes) { q, scopes -> q to scopes }
        .mapLatest { (q, scopes) ->
            val match = SearchText.match(q) ?: return@mapLatest SearchResults()
            val browse = container.database.browse()
            Bench.time("search '$q'") {
                SearchResults(
                    artists = browse.searchArtists(match, SEARCH_ARTISTS, scopes),
                    albums = browse.searchAlbums(match, SEARCH_ALBUMS, scopes),
                    // Titles with every word first, then words spread over title, artist and album.
                    songs = (browse.searchSongs(SearchText.titleMatch(q)!!, SEARCH_SONGS, scopes) + browse.searchSongs(match, SEARCH_SONGS, scopes))
                        .distinctBy { it.id }.take(SEARCH_SONGS),
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults())

    /** Online catalog search on Deezer and Apple Music when the catalog source is enabled and ticked. */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val catalogSearchResults: StateFlow<com.kike.musicplayer.data.catalog.CatalogSearchResults> = combine(
        _query.debounce { if (it.isBlank()) 0L else CATALOG_SEARCH_DEBOUNCE_MS },
        settings.catalogEnabled,
        settings.catalogShown,
    ) { q, enabled, shown -> Triple(q, enabled, shown) }
        .flatMapLatest { (q, enabled, shown) ->
            if (!enabled || !shown || q.trim().length < 2) {
                flowOf(com.kike.musicplayer.data.catalog.CatalogSearchResults())
            } else {
                kotlinx.coroutines.flow.flow {
                    emit(com.kike.musicplayer.data.catalog.CatalogSearchResults(loading = true))
                    emit(container.catalog.searchCatalog(q, includePlaylists = true))
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.kike.musicplayer.data.catalog.CatalogSearchResults())

    /** Saved public Deezer playlists. */
    val savedCatalogPlaylists: StateFlow<List<com.kike.musicplayer.data.db.SavedCatalogPlaylist>> =
        container.database.savedCatalogPlaylists().observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())



    private val history = container.database.history().observeAll()

    private val artistTags = container.database.artistInfo().observeAll().map(::artistTagMap)

    private val database = container.database
    private val favouriteRows = database.favourites().observeAll()

    /**
     * Once at start and again whenever songs or their summaries change (debounced, so a sync
     * doesn't rebuild everything per song): the lists below are rebuilt from the database then.
     */
    private var initialTracksEmission = true

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val tracksChanged: Flow<Unit> = combine(
        database.invalidationTracker.createFlow("tracks", "album_index"),
        // A server sync writes pages for minutes: rebuild once it's done instead.
        container.serverLibrary.status.map { it.running }.distinctUntilChanged(),
    ) { _, syncing -> syncing }
        .filter { syncing -> !syncing }
        .debounce {
            if (initialTracksEmission) {
                initialTracksEmission = false
                0L
            } else {
                TRACKS_CHANGED_DEBOUNCE_MS
            }
        }
        .map { }

    /** Recently added albums: read again when songs change or a source is switched, not after each play. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val addedAlbums: Flow<List<Album>> = combine(viewScopes, tracksChanged) { scopes, _ -> scopes }
        .mapLatest { scopes ->
            Bench.time("added albums") {
                // Each album once (lists are keyed by album), from the source it was added to last.
                val keys = browse.newestSongAlbums(scopes, ADDED_LOOKUP)
                    .distinctBy { it.key }.take(RECENT_ALBUMS).map { it.scope to it.key }
                albumsWithTracks(keys)
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playlists: StateFlow<Playlists> = combine(
        combine(history, favouriteRows, tracksChanged) { h, f, _ -> h to f }
            .mapLatest { (history, favourites) -> Bench.time("playlists") { buildPlaylists(history, favourites) } },
        addedAlbums.onStart { emit(emptyList()) },
    ) { playlists, added -> playlists.copy(addedAlbums = added) }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, Playlists())

    /** Recently played, recent albums, most played and favourites: only their songs are read. */
    private suspend fun buildPlaylists(history: List<PlayHistory>, favourites: List<Favourite>): Playlists {
        val now = System.currentTimeMillis()
        val stats = ListeningStats(history, now, container.historicalDates.csvDates.value, container.historicalDates.inAppDates.value)
        // Like everywhere else, a play counts once half the song was listened to (the playing one joins then).
        val recentIds = history.asSequence().filter { it.counted }.map { it.trackId }.distinct().take(RECENT_LOOKUP).toList()
        val topIds = stats.plays.entries.sortedByDescending { it.value }.take(MOST_PLAYED_LOOKUP).map { it.key }
        val heavyRotationIds = heavyRotationCandidateIds(history, now, candidateLimit = 150)
        val byId = database.tracks().getByIds((recentIds + topIds + heavyRotationIds + favourites.map { it.trackId }).toSet())
            .filter { it.durationMs >= 0 }.associateBy { it.id }
        // Each album once (lists are keyed by album), from the source it was played from.
        val albumKeys = recentIds.mapNotNull { id -> byId[id]?.let { it.scope to it.albumKey } }
            .distinctBy { it.second }.take(RECENT_ALBUMS)
        return Playlists(
            recentTracks = recentIds.mapNotNull { byId[it] }.take(RECENT_TRACKS),
            recentAlbums = albumsWithTracks(albumKeys),
            heavyRotation = heavyRotation(byId, history, now),
            mostPlayed = mostPlayed(byId, stats),
            favourites = preferOwnCopies(favourites.mapNotNull { byId[it.trackId] }),
        )
    }

    /** These albums (scope and key, in this order) with their songs from that scope. */
    private suspend fun albumsWithTracks(keys: List<Pair<Int, String>>): List<Album> {
        if (keys.isEmpty()) return emptyList()
        val albumKeys = keys.map { it.second }.distinct()
        val summaries = browse.albumsByKeys(albumKeys).associateBy { it.key }
        val songs = browse.tracksOfAlbums(albumKeys, Track.SCOPES).groupBy { it.scope to it.indexAlbum }
        return keys.mapNotNull { key ->
            val a = summaries[key.second] ?: return@mapNotNull null
            Album(a.key, a.title, a.artist, a.year, a.artworkUri, songs[key].orEmpty().sortedWith(albumTrackOrder))
        }
    }

    private val savedMixes = container.savedMixes
    private val mixRequests = MutableStateFlow(0)
    private var mixRequestsDone = 0

    private val _mixesRefreshing = MutableStateFlow(false)
    /** True while new mixes are being made after a pull to refresh. */
    val mixesRefreshing: StateFlow<Boolean> = _mixesRefreshing.asStateFlow()

    /** Makes new mixes now (pull to refresh on Home). */
    fun refreshMixes() {
        _mixesRefreshing.value = true
        mixRequests.value++
        if (settings.deezerHomeEnrichment.value) {
            loadDeezerHome(forceRefresh = true)
        }
    }

    /**
     * Generated mixes for the Home tab: made of songs from the sources switched on in the library
     * menu, while plays and favourites from every source show the taste. Once made they're kept
     * (saved, so also across restarts) and only their songs' details follow library changes; new
     * ones are made on a pull to refresh, after [MIX_LIFETIME_MS], or when the sources change.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mixes: StateFlow<List<Mix>> = combine(viewScopes, mixRequests, tracksChanged) { scopes, requests, _ -> scopes to requests }
        .mapLatest { (scopes, requests) ->
            // Recommendations are a nice-to-have: a bug there must never take the app down.
            runCatching {
                val saved = savedMixes.load()
                val asked = requests > mixRequestsDone
                val fresh = saved != null && saved.scopes == scopes && System.currentTimeMillis() - saved.madeAt < MIX_LIFETIME_MS
                if (!asked && fresh) return@runCatching savedMixesWithTracks(saved)
                val made = makeMixes(scopes)
                // Nothing made yet (e.g. the library is still loading): try again on the next change.
                if (made.isNotEmpty()) {
                    savedMixes.save(SavedMixSet(System.currentTimeMillis(), scopes, made.map { m -> SavedMix(m.id, m.title, m.subtitle, m.tracks.map { it.id }) }))
                }
                mixRequestsDone = requests
                made
            }.onFailure { Log.w(TAG, "Recommender failed", it) }.getOrDefault(emptyList())
                .also { _mixesRefreshing.value = false }
        }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun makeMixes(scopes: List<Int>): List<Mix> {
        val history = history.first()
        val favouriteIds = favouriteRows.first().mapTo(HashSet()) { it.trackId }
        val candidates = MixCandidates.load(database, history, favouriteIds, scopes)
        return Bench.time("mixes from ${candidates.size} songs") {
            Recommender(
                tracks = candidates,
                history = history,
                artistTags = artistTags.first(),
                favourites = favouriteIds,
                pickable = { it.scope in scopes },
                historicalDates = container.historicalDates.csvDates.value,
                inAppPlayDates = container.historicalDates.inAppDates.value,
            ).mixes()
        }
    }

    /** The saved mixes with their songs as they are now (songs gone from the library are left out). */
    private suspend fun savedMixesWithTracks(saved: SavedMixSet): List<Mix> {
        val byId = database.tracks().getByIds(saved.mixes.flatMapTo(HashSet()) { it.trackIds }).associateBy { it.id }
        return saved.mixes.map { m -> Mix(m.id, m.title, m.subtitle, m.trackIds.mapNotNull { byId[it] }.filter { it.durationMs >= 0 }) }
            .filter { it.tracks.isNotEmpty() }
    }

    private val replayPeriod = MutableStateFlow<ReplayPeriod?>(null)

    /** Replay reads the songs needed for the selected period (and caches them across period switches). */
    private val replayTracksCache = java.util.concurrent.ConcurrentHashMap<String, Track>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val replay: StateFlow<Replay?> = combine(history, replayPeriod, tracksChanged) { h, p, _ -> h to p }
        .mapLatest { (history, period) ->
            val zone = java.time.ZoneId.systemDefault()
            val targetYear = period?.year ?: java.time.Instant.now().atZone(zone).year
            val yearStartMs = java.time.LocalDate.of(targetYear, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val yearEndMs = java.time.LocalDate.of(targetYear + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            // On initial load (period == null), only fetch tracks played in the current year for fast Home startup;
            // when a user selects a specific period on ReplayScreen, fetch any remaining history tracks for newArtists stats.
            val neededIds = if (period == null) {
                history.asSequence().filter { it.playedAt in yearStartMs until yearEndMs }.mapTo(HashSet()) { it.trackId }
            } else {
                history.mapTo(HashSet()) { it.trackId }
            }
            val missingIds = neededIds.filter { !replayTracksCache.containsKey(it) }
            if (missingIds.isNotEmpty()) {
                database.tracks().getByIds(missingIds).forEach { replayTracksCache[it.id] = it }
            }
            Bench.time("replay") { replay(history, replayTracksCache, period, zone) }
        }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val cachedTrackIds = MutableStateFlow<Set<String>>(emptySet())

    /** Songs by id for screens that only have media items (Now Playing, the queue). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val trackCache: StateFlow<Map<String, Track>> = combine(cachedTrackIds, tracksChanged) { ids, _ -> ids }
        .mapLatest { ids -> database.tracks().getByIds(ids).associateBy { it.id } }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Keeps these songs (the playing one and the queue) in [trackCache]. */
    fun cacheTracks(ids: Collection<String>) {
        cachedTrackIds.value = ids.toSet()
    }

    /** The first of these names that is an artist in the library (for "go to artist"). */
    suspend fun firstArtist(names: List<String?>): String? {
        val candidates = names.filterNotNull().distinct()
        if (candidates.isEmpty()) return null
        val found = browse.existingArtists(candidates).toSet()
        return candidates.firstOrNull { it in found }
    }

    /** The library album by this artist with this title (ignoring case, accents, punctuation and edition suffixes), if any. */
    suspend fun albumInLibrary(artist: String, title: String): String? {
        val wantedKey = com.kike.musicplayer.data.discover.Names.releaseKey(title)
        val direct = browse.observeArtistAlbums(artist, Track.SCOPES).first()
            .firstOrNull { com.kike.musicplayer.data.discover.Names.releaseKey(it.title) == wantedKey }?.key
        if (direct != null) return direct
        val match = SearchText.match("$artist $title") ?: return null
        return browse.searchAlbums(match, 20, Track.SCOPES)
            .firstOrNull {
                com.kike.musicplayer.data.discover.Names.comparable(it.artist) == com.kike.musicplayer.data.discover.Names.comparable(artist) &&
                    com.kike.musicplayer.data.discover.Names.releaseKey(it.title) == wantedKey
            }?.key
    }

    /** Looks up an album in the Deezer & Apple Music catalog matching [artist] and [title]. */
    suspend fun findCatalogAlbum(artist: String, title: String): com.kike.musicplayer.data.catalog.CatalogAlbum? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val query = "$artist $title".trim()
            if (query.length < 2) return@withContext null
            container.catalog.searchCatalog(query).albums.firstOrNull {
                com.kike.musicplayer.data.discover.Names.releaseKey(it.title) == com.kike.musicplayer.data.discover.Names.releaseKey(title) &&
                    (com.kike.musicplayer.data.discover.Names.comparable(it.artist) == com.kike.musicplayer.data.discover.Names.comparable(artist) || artist.isBlank())
            }
        }

    /**
     * Matches catalog tracks against the user's library using [TrackMatcher], which prefers the
     * user's own copy (Local / Google Drive) over Navidrome and ignores edition suffixes.
     */
    suspend fun matchCatalogTracks(
        tracks: List<com.kike.musicplayer.data.catalog.CatalogTrack>,
        exactAlbumOnly: Boolean = false,
    ): Map<String, Track> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyMap()
        val artists = tracks.flatMap { listOfNotNull(it.artist, it.albumArtist) }.distinct()
        val matcher = TrackMatcher.forCatalog(database.tracks(), database.libraryIndex(), artists)
        val out = HashMap<String, Track>()
        for (t in tracks) {
            matcher.match(t.artist, t.title, t.album.takeIf { it.isNotBlank() }, null, exactAlbumOnly)?.let { matched ->
                out[t.id] = matched
            }
        }
        out
    }

    /** Fetches the tracklist of a catalog album and matches each song against the user's library. */
    private val catalogAlbumCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>>>()

    fun cachedCatalogAlbumTracks(albumId: String): Pair<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>>? =
        catalogAlbumCache[albumId]

    fun clearCatalogAlbumCache() {
        catalogAlbumCache.clear()
    }

    suspend fun catalogAlbumTracks(
        album: com.kike.musicplayer.data.catalog.CatalogAlbum,
        forceRefresh: Boolean = false,
    ): Pair<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>> {
        if (!forceRefresh) {
            catalogAlbumCache[album.id]?.let { return it }
        }
        val tracks = container.catalog.albumTracks(album)
        val result = tracks to matchCatalogTracks(tracks, exactAlbumOnly = true)
        catalogAlbumCache[album.id] = result
        return result
    }

    /** Fetches top songs and discography albums for a catalog artist and matches top songs against the library. */
    suspend fun catalogArtistOverview(
        artist: com.kike.musicplayer.data.catalog.CatalogArtist,
    ): Triple<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>, List<com.kike.musicplayer.data.catalog.CatalogAlbum>> {
        val (top, albums) = container.catalog.artistOverview(artist)
        return Triple(top, matchCatalogTracks(top), albums)
    }

    /** Fetches tracks of a public Deezer playlist (cache-first) and matches each song against the user's library. */
    private val catalogPlaylistCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>>>()

    fun cachedCatalogPlaylistTracks(playlistId: String): Pair<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>>? =
        catalogPlaylistCache[playlistId]

    fun clearCatalogPlaylistCache() {
        catalogPlaylistCache.clear()
    }

    suspend fun catalogPlaylistTracks(
        playlist: com.kike.musicplayer.data.catalog.CatalogPlaylist,
        forceRefresh: Boolean = false,
    ): Pair<List<com.kike.musicplayer.data.catalog.CatalogTrack>, Map<String, Track>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            catalogPlaylistCache[playlist.id]?.let { return@withContext it }
        }
        val saved = container.database.savedCatalogPlaylists().getById(playlist.id)
        val trackListType = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.kike.musicplayer.data.catalog.CatalogTrack::class.java)
        val trackAdapter = container.moshi.adapter<List<com.kike.musicplayer.data.catalog.CatalogTrack>>(trackListType)
        val matchMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        val matchAdapter = container.moshi.adapter<Map<String, String>>(matchMapType)

        // Fast cache path: if saved, has cached tracks, and not force-refreshing or stale (> 7 days)
        val isStale = saved != null && (System.currentTimeMillis() - saved.lastRefreshedAt > 7 * 24 * 3600 * 1000L)
        if (!forceRefresh && saved?.cachedTracksJson != null && !isStale) {
            val cached = runCatching { trackAdapter.fromJson(saved.cachedTracksJson) }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                // Instant library read: load matched tracks directly by ID (same as non-Deezer playlists)
                val cachedMatches = saved.cachedMatchesJson?.let { json ->
                    runCatching { matchAdapter.fromJson(json) }.getOrNull()
                }
                if (cachedMatches != null) {
                    val libTracks = database.tracks().getByIds(cachedMatches.values).associateBy { it.id }
                    val matched = cachedMatches.mapNotNull { (catId, libId) ->
                        libTracks[libId]?.let { catId to it }
                    }.toMap()
                    val result = cached to matched
                    catalogPlaylistCache[playlist.id] = result
                    return@withContext result
                }

                // If not yet cached (e.g. migration from v24), match once and persist immediately
                val matched = matchCatalogTracks(cached)
                val matchMap = matched.mapValues { it.value.id }
                val matchesJson = matchAdapter.toJson(matchMap)
                container.database.savedCatalogPlaylists().updateMatches(saved.id, matched.size, matchesJson)
                val result = cached to matched
                catalogPlaylistCache[playlist.id] = result
                return@withContext result
            }
        }

        // Network fetch from Deezer
        val freshTracks = try {
            container.catalog.playlistTracks(playlist)
        } catch (e: Exception) {
            // Fall back to cache on network failure / offline
            if (saved?.cachedTracksJson != null) {
                runCatching { trackAdapter.fromJson(saved.cachedTracksJson) }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
        }
        val matched = matchCatalogTracks(freshTracks)

        // If saved in DB, persist fresh tracks, matched IDs, and timestamp
        if (saved != null && freshTracks.isNotEmpty()) {
            val json = trackAdapter.toJson(freshTracks)
            val matchMap = matched.mapValues { it.value.id }
            val matchesJson = matchAdapter.toJson(matchMap)
            container.database.savedCatalogPlaylists().updateTracksAndMatches(
                id = playlist.id,
                trackCount = freshTracks.size,
                cachedTracksJson = json,
                matchedCount = matched.size,
                cachedMatchesJson = matchesJson,
                lastRefreshedAt = System.currentTimeMillis(),
            )
        }

        val result = freshTracks to matched
        if (freshTracks.isNotEmpty()) {
            catalogPlaylistCache[playlist.id] = result
        }
        result
    }

    fun saveCatalogPlaylist(
        playlist: com.kike.musicplayer.data.catalog.CatalogPlaylist,
        tracks: List<com.kike.musicplayer.data.catalog.CatalogTrack>,
        matched: Map<String, Track>,
    ) {
        if (tracks.isNotEmpty()) {
            catalogPlaylistCache[playlist.id] = tracks to matched
        }
        viewModelScope.launch(Dispatchers.IO) {
            val trackListType = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.kike.musicplayer.data.catalog.CatalogTrack::class.java)
            val json = container.moshi.adapter<List<com.kike.musicplayer.data.catalog.CatalogTrack>>(trackListType).toJson(tracks)
            val matchMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
            val matchMap = matched.mapValues { it.value.id }
            val matchesJson = container.moshi.adapter<Map<String, String>>(matchMapType).toJson(matchMap)
            container.database.savedCatalogPlaylists().upsert(
                com.kike.musicplayer.data.db.SavedCatalogPlaylist(
                    id = playlist.id,
                    title = playlist.title,
                    creator = playlist.creator,
                    trackCount = if (playlist.trackCount > 0) playlist.trackCount else tracks.size,
                    coverUrl = playlist.coverUrl,
                    source = playlist.source,
                    addedAt = System.currentTimeMillis(),
                    lastRefreshedAt = System.currentTimeMillis(),
                    matchedCount = matched.size,
                    cachedTracksJson = json,
                    cachedMatchesJson = matchesJson,
                )
            )
        }
    }

    fun removeSavedCatalogPlaylist(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.database.savedCatalogPlaylists().delete(id)
        }
    }

    suspend fun importPlaylistFromUrl(url: String): Result<com.kike.musicplayer.data.catalog.CatalogPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = container.catalog.resolvePlaylistUrl(url)
            val matched = matchCatalogTracks(resolved.tracks)
            saveCatalogPlaylist(resolved.playlist, resolved.tracks, matched)
            resolved.playlist
        }
    }

    fun recordPlaylistPlayed(playlistId: Long) = viewModelScope.launch(Dispatchers.IO) {
        playlistDao.updateLastPlayed(playlistId)
    }

    fun recordCatalogPlaylistPlayed(id: String) = viewModelScope.launch(Dispatchers.IO) {
        container.database.savedCatalogPlaylists().updateLastPlayed(id)
    }

    // Single-artist cache: only retains missing catalog albums for the currently active artist,
    // avoiding unbounded memory growth across different artist visits.
    private var cachedMissingArtist: String? = null
    private var cachedMissingDiscography: List<com.kike.musicplayer.data.catalog.CatalogAlbum> = emptyList()

    fun clearActiveArtistCache() {
        cachedArtistName = null
        cachedArtistIndex = null
        cachedArtistAlbums = emptyList()
        cachedArtistTracks = emptyList()
        cachedMissingArtist = null
        cachedMissingDiscography = emptyList()
    }

    fun clearActiveAlbumCache() {
        cachedAlbumKey = null
        cachedAlbumIndex = null
        cachedAlbumTracks = emptyList()
    }

    /** Returns studio/live albums by [artistName] on Deezer/Apple Music that are not already in [ownedTitles]. */
    suspend fun artistMissingDiscography(
        artistName: String,
        ownedTitles: Collection<String>,
        forceRefresh: Boolean = false,
    ): List<com.kike.musicplayer.data.catalog.CatalogAlbum> {
        val key = artistName.lowercase().trim()
        if (!forceRefresh && cachedMissingArtist == key && cachedMissingDiscography.isNotEmpty()) {
            return cachedMissingDiscography
        }
        val ownedKeys = ownedTitles.mapTo(HashSet()) { com.kike.musicplayer.data.discover.Names.releaseKey(it) }
        val missing = container.catalog.artistDiscography(artistName)
            .filter { com.kike.musicplayer.data.discover.Names.releaseKey(it.title) !in ownedKeys }
        cachedMissingArtist = key
        cachedMissingDiscography = missing
        return missing
    }

    /**
     * Songs for the playlist picker: those whose title, artist or album (as chosen) has words
     * starting with the ones typed, A–Z, at most [PICKER_LIMIT].
     */
    suspend fun searchSongsBy(text: String, bySong: Boolean, byArtist: Boolean, byAlbum: Boolean): List<Track> =
        SongSearch.find(database.browse(), text, PICKER_LIMIT, viewScopes.value, bySong, byArtist, byAlbum)

    /** Listed songs per source, for the Sources screen. */
    val sourceCounts: StateFlow<Map<String, Int>> = database.browse().observeSourceCounts().map { list -> list.associate { it.source to it.count } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setReplayPeriod(period: ReplayPeriod) {
        replayPeriod.value = period
    }

    val syncMode = settings.syncMode
    val lastDriveSync = settings.lastDriveSync

    fun setSyncMode(mode: DriveSyncMode) = settings.setSyncMode(mode)

    val scanStatus = repo.status
    val lastError = repo.lastError
    val metadataProgress = repo.metadataProgress
    val pendingMetadata = repo.pendingMetadata.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val unplayableCount = repo.unplayableCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val safFolders = settings.safFolders
    val onlineMetadata = settings.onlineMetadata
    val metaDeezer = settings.metaDeezer
    val metaApple = settings.metaApple
    val metaMusicBrainz = settings.metaMusicBrainz
    val catalogEnabled = settings.catalogEnabled
    val catalogShown = settings.catalogShown
    fun setCatalogEnabled(enabled: Boolean) = settings.setCatalogEnabled(enabled)
    fun toggleCatalogShown() = settings.toggleCatalogShown()

    val deezerHomeEnrichment = settings.deezerHomeEnrichment

    fun setDeezerHomeEnrichment(enabled: Boolean) {
        settings.setDeezerHomeEnrichment(enabled)
        if (enabled && _deezerHomeContent.value == null) {
            loadDeezerHome(forceRefresh = false)
        }
    }

    private val _deezerHomeContent = MutableStateFlow<com.kike.musicplayer.data.catalog.DeezerHomeContent?>(null)
    val deezerHomeContent: StateFlow<com.kike.musicplayer.data.catalog.DeezerHomeContent?> = _deezerHomeContent.asStateFlow()

    fun loadDeezerHome(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!forceRefresh) {
                val cached = container.catalog.cachedDeezerHomeContent()
                if (cached != null) {
                    _deezerHomeContent.value = cached
                    return@launch
                }
            }
            val playCounts = runCatching { database.browse().topGenresByPlayHistory() }.getOrDefault(emptyList())
            val genreCounts: Map<String, Int> = if (playCounts.isNotEmpty()) {
                playCounts.associate { it.name to it.count }
            } else {
                val allGenres = runCatching { database.libraryIndex().allGenres() }.getOrDefault(emptyList())
                val effectiveGenres = if (allGenres.isNotEmpty()) {
                    val own = allGenres.filter { it.scope in listOf(Track.SCOPE_LOCAL, Track.SCOPE_DRIVE) && it.songCount > 0 }
                    if (own.isNotEmpty()) own else allGenres.filter { it.songCount > 0 }
                } else {
                    genreList.value.ifEmpty {
                        runCatching {
                            kotlinx.coroutines.withTimeoutOrNull(2000L) {
                                genreList.filter { it.isNotEmpty() }.first()
                            }
                        }.getOrNull().orEmpty()
                    }
                }

                effectiveGenres
                    .filter { it.name.isNotBlank() && !it.name.contains("unknown", ignoreCase = true) }
                    .groupBy { it.name }
                    .mapValues { (_, list) -> list.sumOf { it.songCount } }
            }

            val content = container.catalog.fetchDeezerHomeContent(
                forceRefresh = forceRefresh,
                genreCounts = genreCounts,
            )
            if (content != null) {
                _deezerHomeContent.value = content
            }
        }
    }
    val darkMode = settings.darkMode
    val accentColor = settings.accentColor
    val fullScreenCover = settings.fullScreenCover
    fun setDarkMode(enabled: Boolean) = settings.setDarkMode(enabled)
    fun setAccentColor(color: String) = settings.setAccentColor(color)
    fun setFullScreenCover(enabled: Boolean) = settings.setFullScreenCover(enabled)
    val sonosDownsample = settings.sonosDownsample
    fun setSonosDownsample(enabled: Boolean) = settings.setSonosDownsample(enabled)
    val loudnessNormalization = settings.loudnessNormalization
    fun setLoudnessNormalization(enabled: Boolean) = settings.setLoudnessNormalization(enabled)
    val crossfade = settings.crossfade
    fun setCrossfade(enabled: Boolean) = settings.setCrossfade(enabled)
    val crossfadeSeconds = settings.crossfadeSeconds
    fun setCrossfadeSeconds(seconds: Int) = settings.setCrossfadeSeconds(seconds)

    data class HistoryImportUiState(
        val inProgress: Boolean = false,
        val progress: Float = 0f,
        val result: ImportStats? = null,
        val error: String? = null,
    )
    private val _historyImportState = MutableStateFlow<HistoryImportUiState?>(null)
    val historyImportState: StateFlow<HistoryImportUiState?> = _historyImportState.asStateFlow()

    fun importPlayHistory(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _historyImportState.value = HistoryImportUiState(inProgress = true, progress = 0.05f)
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw java.lang.IllegalStateException("Could not open file")
                val stats = inputStream.use { stream ->
                    HistoricalPlayImporter.importCsvFromDao(
                        inputStream = stream,
                        trackDao = container.database.tracks(),
                        historicalStore = container.historicalDates,
                        historyDao = container.database.history(),
                        addPlayCounts = false,
                        onProgress = { p ->
                            _historyImportState.update { curr -> curr?.copy(progress = p) }
                        }
                    )
                }
                _historyImportState.value = HistoryImportUiState(inProgress = false, progress = 1f, result = stats)
                refreshMixes()
            } catch (e: Exception) {
                Log.e("LibraryViewModel", "Failed to import play history", e)
                _historyImportState.value = HistoryImportUiState(inProgress = false, error = e.localizedMessage ?: "Import failed")
            }
        }
    }

    fun dismissHistoryImport() {
        _historyImportState.value = null
    }
    val language = settings.language
    fun setLanguage(tag: String) = settings.setLanguage(tag)
    val driveAccount = driveAuth.account
    val driveNeedsReconnect = driveAuth.needsReconnect

    private val _driveMessage = MutableStateFlow<String?>(null)
    val driveMessage: StateFlow<String?> = _driveMessage.asStateFlow()

    val driveFolders = settings.driveFolders

    private val _driveBrowser = MutableStateFlow(DriveBrowserState())
    val driveBrowser: StateFlow<DriveBrowserState> = _driveBrowser.asStateFlow()

    private var started = false

    /** Called once the UI is up: rescan the chosen folders (new/removed files) and resume enrichment. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
            val inApp = container.database.history().inAppLastPlayed()
            if (inApp.isNotEmpty()) {
                container.historicalDates.initializeInAppPlays(inApp.associate { it.trackId to it.timestamp })
            }
        }
        repo.refreshAll()
        container.serverLibrary.syncIfDue()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun refreshAll() = repo.refreshAll()

    // ---- Drive folder browser ----

    /** Opens the browser at its top level ("My Drive" / "Shared with me"). */
    fun openDriveBrowser() {
        _driveBrowser.value = DriveBrowserState()
    }

    fun enterDriveFolder(folder: DriveFolder) = loadDriveFolder(_driveBrowser.value.path + folder)

    /** Returns false when already at the top level (the caller then closes the browser). */
    fun driveBrowserBack(): Boolean {
        val path = _driveBrowser.value.path
        if (path.isEmpty()) return false
        if (path.size == 1) _driveBrowser.value = DriveBrowserState() else loadDriveFolder(path.dropLast(1))
        return true
    }

    private fun loadDriveFolder(path: List<DriveFolder>) {
        _driveBrowser.value = DriveBrowserState(path = path, loading = true)
        viewModelScope.launch {
            val id = path.last().id
            val result = runCatching { repo.listDriveFolders(if (id == DriveBrowserState.SHARED_ID) null else id) }
            if (_driveBrowser.value.path != path) return@launch // user navigated away meanwhile
            _driveBrowser.value = result.fold(
                onSuccess = { DriveBrowserState(path = path, children = it) },
                onFailure = { DriveBrowserState(path = path, error = "Couldn't list folders: ${it.message}") },
            )
        }
    }

    /** Adds the folder currently shown in the browser as a music source and syncs Drive. */
    fun selectCurrentDriveFolder(onDone: () -> Unit) {
        val path = _driveBrowser.value.path
        val current = path.lastOrNull() ?: return
        if (current.id == DriveBrowserState.SHARED_ID) return
        viewModelScope.launch {
            try {
                // Files list My Drive's real ID as their parent, never the "root" alias.
                val id = if (current.id == DriveBrowserState.MY_DRIVE_ID) repo.resolveDriveFolderId(current.id) else current.id
                settings.addDriveFolder(DriveFolder(id, path.joinToString(" / ") { it.name }))
                onDone()
                repo.refreshDrive()
            } catch (e: Exception) {
                _driveBrowser.value = _driveBrowser.value.copy(error = "Couldn't add folder: ${e.message}")
            }
        }
    }

    fun removeDriveFolder(id: String) {
        settings.removeDriveFolder(id)
        viewModelScope.launch { repo.refreshDrive() }
    }

    fun addFolder(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.addSafFolder(uri.toString())
        viewModelScope.launch { repo.refreshFolders() }
    }

    fun removeFolder(uri: String) {
        settings.removeSafFolder(uri)
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch { repo.refreshFolders() }
    }

    /** The account Drive was connected with before a reconnect, to warn if another one is picked. */
    private var accountBeforeConnect: String? = null

    /**
     * Starts the Drive OAuth flow; also reconnects after Google asks to sign in again (nothing is
     * removed). If Google needs the user to pick an account or consent,
     * [launchConsent] receives the PendingIntent to start; its result goes to [onDriveConsentResult].
     */
    fun connectDrive(launchConsent: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            try {
                accountBeforeConnect = driveAuth.account.value
                val result = driveAuth.authorize()
                if (result.hasResolution()) launchConsent(result.pendingIntent!!)
                else finishDriveConnect(driveAuth.onAuthorized(result))
            } catch (e: Exception) {
                Log.w(TAG, "Drive authorization failed", e)
                _driveMessage.value = "Google sign-in failed: ${e.message}. See README for the Cloud Console setup."
            }
        }
    }

    fun onDriveConsentResult(data: Intent?) {
        try {
            finishDriveConnect(driveAuth.onAuthorized(driveAuth.resultFromIntent(data)))
        } catch (e: Exception) {
            Log.w(TAG, "Drive consent failed", e)
            _driveMessage.value = "Google sign-in was cancelled or failed."
        }
    }

    private fun finishDriveConnect(ok: Boolean) {
        if (!ok) {
            _driveMessage.value = "Google did not return an access token."
            return
        }
        val before = accountBeforeConnect
        val now = driveAuth.account.value
        _driveMessage.value = if (!before.isNullOrBlank() && !now.isNullOrBlank() && !before.equals(now, ignoreCase = true)) {
            "Connected as $now, but your Drive folders belong to $before. Disconnect and connect $before again if songs don't play."
        } else null
        // Nothing is imported until the user picks folders (unless some are already chosen).
        if (settings.driveFolders.value.isNotEmpty()) viewModelScope.launch { repo.refreshDrive() }
    }

    fun refreshDrive() = viewModelScope.launch { repo.refreshDrive() }

    fun disconnectDrive(deleteDownloads: Boolean) = viewModelScope.launch { repo.removeDrive(deleteDownloads) }

    /** Counted plays (half listened) per track id, for the artist page's top songs. */
    val playCounts: StateFlow<Map<String, Int>> = history
        .map { list -> list.filter { it.counted }.groupingBy { it.trackId }.eachCount() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Artist photo URLs by lower-cased name, for the Artists tab thumbnails. */
    val artistImageUrls: StateFlow<Map<String, String>> = container.artistImages.urls
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Dynamically looked-up album cover URLs by album key, for grid thumbnails before Room flush. */
    val albumCoverUrls: StateFlow<Map<String, String>> = container.enricher.albumCovers

    /** The artist's photo (looked up online once, then remembered), or null; also fetches missing artist genres. */
    suspend fun artistImage(name: String): String? {
        viewModelScope.launch { container.enricher.ensureArtistInfo(name) }
        return container.artistImages.imageFor(name)
    }

    /** Dynamically looks up a missing album cover (and year/genre) as the user scrolls or opens an album. */
    suspend fun albumCover(key: String, title: String, artist: String): String? =
        container.enricher.albumCover(key, title, artist)

    /** Dynamically enriches an opened album and writes any found cover, year or genre to Room immediately. */
    suspend fun enrichAlbum(key: String, title: String, artist: String) =
        container.enricher.enrichAlbum(key, title, artist)

    // ---- User playlists ----

    private val playlistDao = container.database.playlists()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val userPlaylists: StateFlow<List<PlaylistView>> = combine(
        playlistDao.observePlaylists(), playlistDao.observeEntries(), tracksChanged,
    ) { playlists, entries, _ -> playlists to entries }
        .mapLatest { (playlists, entries) ->
            val byId = database.tracks().getByIds(entries.mapTo(HashSet()) { it.trackId }).associateBy { it.id }
            val songs = entries.groupBy({ it.playlistId }, { it.trackId })
            playlists.map { p -> PlaylistView(p.id, p.name, songs[p.id].orEmpty().mapNotNull { byId[it] }, p.lastPlayedAt, p.createdAt) }
        }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A free default name: "Playlist 1", "Playlist 2"… */
    fun nextPlaylistName(): String {
        val taken = userPlaylists.value.mapTo(HashSet()) { it.name }
        return generateSequence(userPlaylists.value.size + 1) { it + 1 }.map { "Playlist $it" }.first { it !in taken }
    }

    fun createPlaylist(name: String, trackIds: List<String>, tracks: List<Track> = emptyList()) = viewModelScope.launch(Dispatchers.IO) {
        val streamTracks = tracks.filter { it.source == Track.SOURCE_NEBULA || it.scope == Track.SCOPE_STREAM }
            .map { it.copy(scope = Track.SCOPE_STREAM, metadataState = Track.META_DONE) }
        if (streamTracks.isNotEmpty()) {
            database.tracks().upsertAll(streamTracks)
        }
        playlistDao.create(name.trim().ifEmpty { nextPlaylistName() }, trackIds)
    }

    fun addToPlaylist(playlistId: Long, trackIds: List<String>, tracks: List<Track> = emptyList()) = viewModelScope.launch(Dispatchers.IO) {
        val streamTracks = tracks.filter { it.source == Track.SOURCE_NEBULA || it.scope == Track.SCOPE_STREAM }
            .map { it.copy(scope = Track.SCOPE_STREAM, metadataState = Track.META_DONE) }
        if (streamTracks.isNotEmpty()) {
            database.tracks().upsertAll(streamTracks)
        }
        playlistDao.append(playlistId, trackIds)
    }

    fun deletePlaylist(playlistId: Long) = viewModelScope.launch { playlistDao.delete(playlistId) }
    fun renamePlaylist(playlistId: Long, name: String) = viewModelScope.launch {
        name.trim().takeIf { it.isNotEmpty() }?.let { playlistDao.rename(playlistId, it) }
    }
    fun removeFromPlaylist(playlistId: Long, trackId: String) = viewModelScope.launch { playlistDao.remove(playlistId, trackId) }
    fun reorderPlaylist(playlistId: Long, trackIds: List<String>) = viewModelScope.launch { playlistDao.reorder(playlistId, trackIds) }

    // ---- Downloads ----

    private val downloader = container.downloader
    val downloadProgress = downloader.progress
    fun download(tracks: List<Track>) = viewModelScope.launch(Dispatchers.IO) {
        val streamTracks = tracks.filter { it.source == Track.SOURCE_NEBULA || it.scope == Track.SCOPE_STREAM }
            .map { it.copy(scope = Track.SCOPE_STREAM, metadataState = Track.META_DONE) }
        if (streamTracks.isNotEmpty()) {
            if (!addonsConnected.value) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), com.kike.musicplayer.R.string.no_available_sources_to_play, android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            database.tracks().upsertAll(streamTracks)
        }
        downloader.download(tracks)
    }
    fun removeDownload(track: Track) = downloader.remove(track)
    val downloadTotals = downloader.totals.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun removeAllDownloads() = downloader.removeAll()

    // ---- Browsing: straight from the database, page by page (large libraries) ----

    private val browse = container.database.browse()

    /** Pages of 60; placeholders give lists their full length, so the A–Z bar can jump anywhere. */
    private val paging = PagingConfig(pageSize = 60, initialLoadSize = 120, enablePlaceholders = true, jumpThreshold = 240)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val songPages: Flow<PagingData<Track>> = viewScopes.flatMapLatest { s -> Pager(paging) { browse.songs(s) }.flow }.cachedIn(viewModelScope)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val albumPages: Flow<PagingData<AlbumIndex>> = viewScopes.flatMapLatest { s -> Pager(paging) { browse.albums(s) }.flow }.cachedIn(viewModelScope)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val artistPages: Flow<PagingData<ArtistIndex>> = viewScopes.flatMapLatest { s -> Pager(paging) { browse.artists(s) }.flow }.cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> inView(block: (List<Int>) -> Flow<T>): Flow<T> = viewScopes.flatMapLatest(block)

    val songJumps: StateFlow<List<Jump>> = inView { browse.songFirstChars(it) }.map(::jumps).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val albumJumps: StateFlow<List<Jump>> = inView { browse.albumFirstChars(it) }.map(::jumps).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val artistJumps: StateFlow<List<Jump>> = inView { browse.artistFirstChars(it) }.map(::jumps).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True while the album, artist and genre summaries are being brought up to date. */
    val indexing: StateFlow<Boolean> = container.indexer.busy

    val songCount: StateFlow<Int> = inView { browse.observeSongCount(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val genreList: StateFlow<List<GenreIndex>> = inView { browse.observeGenres(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The scopes a detail page shows: the library view's, or, when the album, artist or genre has
     * no songs there (e.g. opened from a song of the other source), all those it has.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> detail(present: Flow<List<Int>>, block: (List<Int>) -> Flow<T>): Flow<T> =
        combine(viewScopes, present) { view, has -> view.filter { it in has }.ifEmpty { has.distinct().sorted().ifEmpty { view } } }
            .distinctUntilChanged().flatMapLatest(block)

    fun genre(key: String): Flow<GenreIndex?> = detail(browse.observeGenreScopes(key)) { browse.observeGenre(key, it) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun genreAlbumPages(key: String): Flow<PagingData<AlbumIndex>> =
        detail(browse.observeGenreScopes(key)) { s -> Pager(paging) { browse.genreAlbums(key, s) }.flow }

    private var cachedAlbumKey: String? = null
    private var cachedAlbumIndex: AlbumIndex? = null
    private var cachedAlbumTracks: List<Track> = emptyList()

    fun cachedAlbumIndex(key: String): AlbumIndex? =
        if (cachedAlbumKey == key) cachedAlbumIndex else null

    fun cachedAlbumTracks(key: String): List<Track> =
        if (cachedAlbumKey == key) cachedAlbumTracks else emptyList()

    fun album(key: String): Flow<AlbumIndex?> = detail(browse.observeAlbumScopes(key)) { browse.observeAlbum(key, it) }
        .onEach {
            if (cachedAlbumKey != key) {
                cachedAlbumKey = key
                cachedAlbumTracks = emptyList()
            }
            cachedAlbumIndex = it
        }

    /** The album's songs in album order; only those of [genreKey] when given. */
    fun albumTracks(key: String, genreKey: String? = null): Flow<List<Track>> =
        detail(browse.observeAlbumScopes(key)) { s ->
            if (genreKey == null) browse.observeAlbumTracks(key, s) else browse.observeAlbumTracksInGenre(key, genreKey, s)
        }.map { it.sortedWith(albumTrackOrder) }
            .onEach { if (cachedAlbumKey == key && genreKey == null) cachedAlbumTracks = it }

    private var cachedArtistName: String? = null
    private var cachedArtistIndex: com.kike.musicplayer.data.db.ArtistIndex? = null
    private var cachedArtistAlbums: List<com.kike.musicplayer.data.db.AlbumIndex> = emptyList()
    private var cachedArtistTracks: List<Track> = emptyList()

    fun cachedArtistIndex(name: String): com.kike.musicplayer.data.db.ArtistIndex? =
        if (cachedArtistName == name) cachedArtistIndex else null
    fun cachedArtistAlbums(name: String): List<com.kike.musicplayer.data.db.AlbumIndex> =
        if (cachedArtistName == name) cachedArtistAlbums else emptyList()
    fun cachedArtistTracks(name: String): List<Track> =
        if (cachedArtistName == name) cachedArtistTracks else emptyList()

    fun artist(name: String): Flow<com.kike.musicplayer.data.db.ArtistIndex?> =
        detail(browse.observeArtistScopes(name)) { browse.observeArtist(name, it) }
            .onEach {
                if (cachedArtistName != name) {
                    cachedArtistName = name
                    cachedArtistAlbums = emptyList()
                    cachedArtistTracks = emptyList()
                }
                cachedArtistIndex = it
            }

    fun artistAlbums(name: String): Flow<List<com.kike.musicplayer.data.db.AlbumIndex>> =
        detail(browse.observeArtistScopes(name)) { browse.observeArtistAlbums(name, it) }
            .onEach { if (cachedArtistName == name) cachedArtistAlbums = it }

    fun artistTracks(name: String): Flow<List<Track>> =
        detail(browse.observeArtistScopes(name)) { browse.observeArtistTracks(name, it) }
            .onEach { if (cachedArtistName == name) cachedArtistTracks = it }
    fun artistTop(name: String): Flow<List<TrackPlays>> =
        detail(browse.observeArtistScopes(name)) { browse.observeArtistTop(name, ARTIST_TOP_SONGS, it) }

    /** A queue for the Songs tab: [QUEUE_LIMIT] songs from position [index] on. */
    suspend fun songsFrom(index: Int): List<Track> = browse.songsFrom(index, QUEUE_LIMIT, viewScopes.value)
    suspend fun randomSongs(): List<Track> = browse.randomSongs(QUEUE_LIMIT, viewScopes.value)
    suspend fun genreTracks(key: String, shuffle: Boolean): List<Track> {
        val scopes = genreScopes(key)
        return if (shuffle) browse.randomGenreTracks(key, QUEUE_LIMIT, scopes) else browse.genreTracks(key, QUEUE_LIMIT, scopes)
    }

    private suspend fun genreScopes(key: String): List<Int> =
        detail(browse.observeGenreScopes(key)) { flowOf(it) }.first()

    // ---- Discover ----

    private val discover = container.discover
    val followedArtists: StateFlow<List<String>> = discover.followed.map { list -> list.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val discoverItems: StateFlow<List<DiscoverItem>> = discover.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val discoverRefreshing = discover.refreshing
    val concertCountry = settings.concertCountry
    fun follow(name: String) = discover.follow(name)
    fun unfollow(name: String) = discover.unfollow(name)
    fun refreshDiscover(force: Boolean) = discover.refresh(force)
    fun setConcertCountry(code: String) {
        if (code == settings.concertCountry.value) return
        settings.setConcertCountry(code)
        discover.refreshConcerts()
    }

    /** The most played artists not followed yet, offered on an empty Discover tab. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val suggestedArtists: StateFlow<List<String>> = followedArtists
        .flatMapLatest { followed ->
            if (followed.isNotEmpty()) {
                flowOf(emptyList())
            } else {
                browse.observeArtistPlayCounts(SUGGESTED_ARTISTS + 50).map { counts ->
                    counts.map { it.name }.take(SUGGESTED_ARTISTS)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- Music Add-ons (Nullbeat, Nebula, etc.) ----

    val addonManager = container.addonManager
    val addons = addonManager.addons
    val addonBusy = addonManager.busy
    val addonMessage = addonManager.message
    val addonUrls = container.nebulaUrls

    fun connectAddon(manifestUrl: String, preferredId: String? = null) =
        viewModelScope.launch { addonManager.connect(manifestUrl, preferredId) }

    fun disconnectAddon(addonId: String) = addonManager.disconnect(addonId)

    fun reorderAddons(fromIndex: Int, toIndex: Int) = addonManager.reorder(fromIndex, toIndex)

    fun setAddonEnabled(addonId: String, enabled: Boolean) = addonManager.setEnabled(addonId, enabled)

    val addonsConnected: StateFlow<Boolean> = addons
        .map { list -> list.any { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Backward compatibility for existing Nebula callers
    val nebulaState: StateFlow<NebulaState> = addons
        .map { list ->
            val first = list.firstOrNull { it.enabled }
            if (first != null) {
                NebulaState(
                    manifestUrl = first.manifestUrl,
                    addonName = first.name,
                    icon = first.icon,
                    quality = first.quality,
                )
            } else {
                NebulaState()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NebulaState())
    val nebulaUrls = container.nebulaUrls
    fun connectNebula(manifestUrl: String) = connectAddon(manifestUrl, "nebula")
    fun disconnectNebula() = disconnectAddon("nebula")

    // ---- Subsonic / Navidrome server ----

    private val subsonic = container.subsonic
    val subsonicState = subsonic.state

    fun connectSubsonic(address: String, username: String, password: String) =
        viewModelScope.launch { subsonic.connect(address, username, password) }

    fun checkSubsonic() = viewModelScope.launch { subsonic.check() }

    /** Signs out and removes the server's songs from the library (plays and favourites stay). */
    val reportServerPlays = subsonic.reportServerPlays
    val reportOtherPlays = subsonic.reportOtherPlays
    fun setReportServerPlays(on: Boolean) = subsonic.setReportServerPlays(on)
    fun setReportOtherPlays(on: Boolean) = subsonic.setReportOtherPlays(on)
    val pendingServerPlays = container.serverPlays.pending.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val starOtherFavourites = subsonic.starOtherFavourites
    val loveServerStars = subsonic.loveServerStars
    fun setStarOtherFavourites(on: Boolean) {
        subsonic.setStarOtherFavourites(on)
        container.serverCollections.syncNow()
    }
    fun setLoveServerStars(on: Boolean) = subsonic.setLoveServerStars(on)
    val collectionsSync = container.serverCollections.status
    fun syncCollections() = container.serverCollections.syncNow()

    /** Navidrome songs downloaded to the phone (offered for deletion when disconnecting). */
    val serverDownloads: StateFlow<Int> = database.tracks().observeDownloadedCount(Track.SOURCE_SUBSONIC)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun disconnectSubsonic(deleteDownloads: Boolean) = viewModelScope.launch {
        if (deleteDownloads) downloader.deleteAll(Track.SOURCE_SUBSONIC)
        container.serverPlays.clear()
        container.serverCollections.forget()
        container.serverLibrary.removeAll()
        subsonic.disconnect()
    }

    private val serverLibrary = container.serverLibrary
    val serverSync = serverLibrary.status
    fun syncServer() = serverLibrary.sync(force = true)
    val serverSyncMode = settings.serverSyncMode
    fun setServerSyncMode(mode: DriveSyncMode) = settings.setServerSyncMode(mode)
    val lastServerSync = settings.lastServerSync

    // ---- Last.fm ----

    private val lastFm = container.lastFm
    val lastFmState = lastFm.state

    /** Gets a sign-in token and hands over the Last.fm approval page to open in the browser. */
    fun connectLastFm(open: (android.net.Uri) -> Unit) = viewModelScope.launch {
        lastFm.startConnect()?.let(open)
    }

    fun finishLastFmConnect(token: String?) = viewModelScope.launch { lastFm.finishConnect(token) }
    fun cancelLastFmConnect() = lastFm.cancelConnect()
    fun disconnectLastFm() = lastFm.disconnect()

    val scrobbleSettings = lastFm.scrobbleSettings
    val pendingScrobbles = container.scrobbler.pending.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun setScrobbleSettings(settings: com.kike.musicplayer.data.lastfm.ScrobbleSettings) = lastFm.setScrobbleSettings(settings)

    private val importer = container.scrobbleImporter
    val importStatus = importer.status

    /** Imported scrobbles of the connected user: total, and how many aren't in the library. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scrobbleCounts = lastFm.state.map { it.user }.distinctUntilChanged()
        .flatMapLatest { user -> if (user == null) flowOf(null) else importer.counts(user) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val autoSync = container.autoSync.state
    fun setAutoSync(on: Boolean) = container.autoSync.setEnabled(on)
    fun syncEverything() = container.autoSync.syncNow()

    fun importScrobbles(everything: Boolean) = importer.import(everything)
    fun importLoved() = importer.importLoved()

    init {
        viewModelScope.launch {
            settings.deezerHomeEnrichment.collect { enabled ->
                if (enabled && _deezerHomeContent.value == null) {
                    loadDeezerHome(forceRefresh = false)
                }
            }
        }

        // Songs added to the library may be ones Last.fm scrobbles are waiting for.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            songCount.drop(1).distinctUntilChanged().debounce(30_000).collect { importer.rematch() }
        }

        // Whenever songs change in Local, Drive or Navidrome, update matches for saved Deezer playlists
        // (on initial startup, only match playlists that don't have cachedMatchesJson yet).
        viewModelScope.launch(Dispatchers.IO) {
            var isFirstEmission = true
            tracksChanged.collect {
                val first = isFirstEmission
                isFirstEmission = false
                if (!first) {
                    replayTracksCache.clear()
                }
                val savedList = container.database.savedCatalogPlaylists().observeAll().first()
                if (savedList.isEmpty()) return@collect
                val toUpdate = if (first) savedList.filter { it.cachedMatchesJson == null } else savedList
                if (toUpdate.isEmpty()) return@collect
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.kike.musicplayer.data.catalog.CatalogTrack::class.java)
                val adapter = container.moshi.adapter<List<com.kike.musicplayer.data.catalog.CatalogTrack>>(type)
                val matchMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                val matchAdapter = container.moshi.adapter<Map<String, String>>(matchMapType)
                for (saved in toUpdate) {
                    val json = saved.cachedTracksJson ?: continue
                    val tracks = runCatching { adapter.fromJson(json) }.getOrNull().orEmpty()
                    if (tracks.isEmpty()) continue
                    val matched = matchCatalogTracks(tracks)
                    val matchMap = matched.mapValues { it.value.id }
                    val matchesJson = matchAdapter.toJson(matchMap)
                    container.database.savedCatalogPlaylists().updateMatches(saved.id, matched.size, matchesJson)
                }
            }
        }
    }

    fun setMetaDeezer(enabled: Boolean) = settings.setMetaDeezer(enabled)
    fun setMetaApple(enabled: Boolean) = settings.setMetaApple(enabled)
    fun setMetaMusicBrainz(enabled: Boolean) = settings.setMetaMusicBrainz(enabled)

    fun setOnlineMetadata(enabled: Boolean) {
        settings.setOnlineMetadata(enabled)
    }

    fun retryMetadata() = repo.retryIncompleteMetadata()

    private companion object {
        const val TAG = "LibraryViewModel"
        const val RECENT_TRACKS = 30
        /** Albums on the recently played / added pages (Home's rows show the first few). */
        const val RECENT_ALBUMS = 30
        /** Distinct recently played songs read for Recently Played and the recent albums. */
        const val RECENT_LOOKUP = 600
        /** Newest songs read for the recently added albums (a few hundred albums' worth). */
        const val ADDED_LOOKUP = 3000
        /** Mixes are made again after two days. */
        const val MIX_LIFETIME_MS = 48 * 60 * 60 * 1000L
        const val MOST_PLAYED_LOOKUP = 200
        const val TRACKS_CHANGED_DEBOUNCE_MS = 500L
        const val SUGGESTED_ARTISTS = 8
        /** Songs queued at most by Play / Shuffle of a whole tab or genre. */
        const val QUEUE_LIMIT = 1000
        const val ARTIST_TOP_SONGS = 10
        const val SEARCH_DEBOUNCE_MS = 150L
        const val CATALOG_SEARCH_DEBOUNCE_MS = 400L
        /** Search results shown at most per kind. */
        const val SEARCH_ARTISTS = 30
        const val SEARCH_ALBUMS = 50
        const val SEARCH_SONGS = 100
        const val PICKER_LIMIT = 500
    }
}

/**
 * Each song once on album and artist pages when several sources are shown: the user's own copy
 * (device, Drive) wherever there is one, Navidrome's only for songs they haven't got (e.g. bonus
 * tracks). "The same song" as everywhere else: artist and title, notes like "(Remastered)" ignored,
 * live and demo versions apart. Order is kept.
 */
internal fun preferOwnCopies(tracks: List<Track>): List<Track> {
    if (tracks.none { it.source == Track.SOURCE_SUBSONIC } || tracks.all { it.source == Track.SOURCE_SUBSONIC }) return tracks
    fun key(t: Track) = TrackMatcher.looseArtist(t.albumArtist ?: t.artist ?: "") + "\u0001" + TrackMatcher.looseTitle(t.title)
    val own = tracks.filter { it.source != Track.SOURCE_SUBSONIC }.mapTo(HashSet(), ::key)
    return tracks.filter { it.source != Track.SOURCE_SUBSONIC || key(it) !in own }
}
