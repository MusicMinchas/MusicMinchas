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
import com.kike.musicplayer.recommend.replay
import com.kike.musicplayer.playback.albumTrackOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Counted plays (half the track listened), most first. */
    val mostPlayed: List<Ranked<Track>> = emptyList(),
    /** Favourite songs, most recently added first. */
    val favourites: List<Track> = emptyList(),
)

/** A user playlist with its songs (those still in the library), in order. */
data class PlaylistView(val id: Long, val name: String, val tracks: List<Track>)

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

    private val history = container.database.history().observeAll()

    private val artistTags = container.database.artistInfo().observeAll().map(::artistTagMap)

    private val database = container.database
    private val favouriteRows = database.favourites().observeAll()

    /**
     * Once at start and again whenever songs or their summaries change (debounced, so a sync
     * doesn't rebuild everything per song): the lists below are rebuilt from the database then.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val tracksChanged: Flow<Unit> = combine(
        database.invalidationTracker.createFlow("tracks", "album_index"),
        // A server sync writes pages for minutes: rebuild once it's done instead.
        container.serverLibrary.status.map { it.running }.distinctUntilChanged(),
    ) { _, syncing -> syncing }
        .filter { syncing -> !syncing }
        .debounce(TRACKS_CHANGED_DEBOUNCE_MS)
        .map { }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playlists: StateFlow<Playlists> = combine(history, favouriteRows, tracksChanged) { h, f, _ -> h to f }
        .mapLatest { (history, favourites) -> Bench.time("playlists") { buildPlaylists(history, favourites) } }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, Playlists())

    /** Recently played, recent albums, most played and favourites: only their songs are read. */
    private suspend fun buildPlaylists(history: List<PlayHistory>, favourites: List<Favourite>): Playlists {
        val stats = ListeningStats(history, System.currentTimeMillis())
        // Like everywhere else, a play counts once half the song was listened to (the playing one joins then).
        val recentIds = history.asSequence().filter { it.counted }.map { it.trackId }.distinct().take(RECENT_LOOKUP).toList()
        val topIds = stats.plays.entries.sortedByDescending { it.value }.take(MOST_PLAYED_LOOKUP).map { it.key }
        val byId = database.tracks().getByIds((recentIds + topIds + favourites.map { it.trackId }).toSet())
            .filter { it.durationMs >= 0 }.associateBy { it.id }
        val albumKeys = recentIds.mapNotNull { id -> byId[id]?.let { it.scope to it.albumKey } }.distinct().take(RECENT_ALBUMS)
        return Playlists(
            recentTracks = recentIds.mapNotNull { byId[it] }.take(RECENT_TRACKS),
            recentAlbums = albumsWithTracks(albumKeys),
            mostPlayed = mostPlayed(byId, stats),
            favourites = favourites.mapNotNull { byId[it.trackId] },
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
                if (!asked && fresh) return@runCatching savedMixesWithTracks(saved!!)
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
            Recommender(candidates, history, artistTags.first(), favourites = favouriteIds, pickable = { it.scope in scopes }).mixes()
        }
    }

    /** The saved mixes with their songs as they are now (songs gone from the library are left out). */
    private suspend fun savedMixesWithTracks(saved: SavedMixSet): List<Mix> {
        val byId = database.tracks().getByIds(saved.mixes.flatMapTo(HashSet()) { it.trackIds }).associateBy { it.id }
        return saved.mixes.map { m -> Mix(m.id, m.title, m.subtitle, m.trackIds.mapNotNull { byId[it] }.filter { it.durationMs >= 0 }) }
            .filter { it.tracks.isNotEmpty() }
    }

    private val replayPeriod = MutableStateFlow<ReplayPeriod?>(null)

    /** Replay reads only the songs in the play history. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val replay: StateFlow<Replay?> = combine(history, replayPeriod, tracksChanged) { h, p, _ -> h to p }
        .mapLatest { (history, period) ->
            val byId = database.tracks().getByIds(history.mapTo(HashSet()) { it.trackId }).associateBy { it.id }
            Bench.time("replay") { replay(history, byId, period) }
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

    /** The library album by this artist with this title (ignoring case, accents and punctuation), if any. */
    suspend fun albumInLibrary(artist: String, title: String): String? =
        browse.observeArtistAlbums(artist, Track.SCOPES).first()
            .firstOrNull { com.kike.musicplayer.data.discover.Names.comparable(it.title) == com.kike.musicplayer.data.discover.Names.comparable(title) }?.key

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

    /** The artist's photo (looked up online once, then remembered), or null. */
    suspend fun artistImage(name: String): String? = container.artistImages.imageFor(name)

    // ---- User playlists ----

    private val playlistDao = container.database.playlists()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val userPlaylists: StateFlow<List<PlaylistView>> = combine(
        playlistDao.observePlaylists(), playlistDao.observeEntries(), tracksChanged,
    ) { playlists, entries, _ -> playlists to entries }
        .mapLatest { (playlists, entries) ->
            val byId = database.tracks().getByIds(entries.mapTo(HashSet()) { it.trackId }).associateBy { it.id }
            val songs = entries.groupBy({ it.playlistId }, { it.trackId })
            playlists.map { p -> PlaylistView(p.id, p.name, songs[p.id].orEmpty().mapNotNull { byId[it] }) }
        }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A free default name: "Playlist 1", "Playlist 2"… */
    fun nextPlaylistName(): String {
        val taken = userPlaylists.value.mapTo(HashSet()) { it.name }
        return generateSequence(userPlaylists.value.size + 1) { it + 1 }.map { "Playlist $it" }.first { it !in taken }
    }

    fun createPlaylist(name: String, trackIds: List<String>) = viewModelScope.launch {
        playlistDao.create(name.trim().ifEmpty { nextPlaylistName() }, trackIds)
    }

    fun addToPlaylist(playlistId: Long, trackIds: List<String>) = viewModelScope.launch { playlistDao.append(playlistId, trackIds) }

    fun deletePlaylist(playlistId: Long) = viewModelScope.launch { playlistDao.delete(playlistId) }
    fun renamePlaylist(playlistId: Long, name: String) = viewModelScope.launch {
        name.trim().takeIf { it.isNotEmpty() }?.let { playlistDao.rename(playlistId, it) }
    }
    fun removeFromPlaylist(playlistId: Long, trackId: String) = viewModelScope.launch { playlistDao.remove(playlistId, trackId) }
    fun reorderPlaylist(playlistId: Long, trackIds: List<String>) = viewModelScope.launch { playlistDao.reorder(playlistId, trackIds) }

    // ---- Downloads ----

    private val downloader = container.downloader
    val downloadProgress = downloader.progress
    fun download(tracks: List<Track>) = downloader.download(tracks)
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

    fun album(key: String): Flow<AlbumIndex?> = detail(browse.observeAlbumScopes(key)) { browse.observeAlbum(key, it) }

    /** The album's songs in album order; only those of [genreKey] when given. */
    fun albumTracks(key: String, genreKey: String? = null): Flow<List<Track>> =
        detail(browse.observeAlbumScopes(key)) { s ->
            if (genreKey == null) browse.observeAlbumTracks(key, s) else browse.observeAlbumTracksInGenre(key, genreKey, s)
        }.map { preferOwnCopies(it).sortedWith(albumTrackOrder) }

    fun artist(name: String): Flow<ArtistIndex?> = detail(browse.observeArtistScopes(name)) { browse.observeArtist(name, it) }
    fun artistAlbums(name: String): Flow<List<AlbumIndex>> = detail(browse.observeArtistScopes(name)) { browse.observeArtistAlbums(name, it) }
    fun artistTracks(name: String): Flow<List<Track>> =
        detail(browse.observeArtistScopes(name)) { browse.observeArtistTracks(name, it) }.map(::preferOwnCopies)
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
    val suggestedArtists: StateFlow<List<String>> = combine(
        browse.observeArtistPlayCounts(SUGGESTED_ARTISTS + 50), followedArtists,
    ) { counts, followed -> counts.map { it.name }.filter { it !in followed }.take(SUGGESTED_ARTISTS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        // Songs added to the library may be ones Last.fm scrobbles are waiting for.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            songCount.drop(1).distinctUntilChanged().debounce(30_000).collect { importer.rematch() }
        }
    }

    fun setOnlineMetadata(enabled: Boolean) {
        settings.setOnlineMetadata(enabled)
        if (enabled) repo.enrich()
    }

    fun retryMetadata() = repo.retryIncompleteMetadata()

    private companion object {
        const val TAG = "LibraryViewModel"
        const val RECENT_TRACKS = 30
        const val RECENT_ALBUMS = 10
        /** Distinct recently played songs read for Recently Played and the recent albums. */
        const val RECENT_LOOKUP = 300
        /** Mixes are made again after two days. */
        const val MIX_LIFETIME_MS = 48 * 60 * 60 * 1000L
        const val MOST_PLAYED_LOOKUP = 200
        const val TRACKS_CHANGED_DEBOUNCE_MS = 500L
        const val SUGGESTED_ARTISTS = 8
        /** Songs queued at most by Play / Shuffle of a whole tab or genre. */
        const val QUEUE_LIMIT = 1000
        const val ARTIST_TOP_SONGS = 10
        const val SEARCH_DEBOUNCE_MS = 150L
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
