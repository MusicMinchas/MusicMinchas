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
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.drive.DriveFolder
import com.kike.musicplayer.recommend.ListeningStats
import com.kike.musicplayer.recommend.Mix
import com.kike.musicplayer.recommend.Ranked
import com.kike.musicplayer.recommend.Recommender
import com.kike.musicplayer.recommend.effectiveGenreKey
import com.kike.musicplayer.recommend.Replay
import com.kike.musicplayer.recommend.ReplayPeriod
import com.kike.musicplayer.recommend.mostPlayed
import com.kike.musicplayer.recommend.replay
import com.kike.musicplayer.playback.albumTrackOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

data class Artist(val name: String, val tracks: List<Track>, val albumCount: Int)

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
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Track> = emptyList(),
)

data class Genre(
    /** Lower-cased genre (or [UNKNOWN_GENRE]). */
    val key: String,
    val name: String,
    val albums: List<Album>,
    val songCount: Int,
    val artistCount: Int,
)

const val UNKNOWN_GENRE = "\u0000unknown"

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

data class Library(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val repo = container.library
    private val settings = container.settings
    private val driveAuth = container.driveAuth

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val library: StateFlow<Library> = repo.tracks
        .map(::buildLibrary)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Library())

    /** Library search, by artist name, album title and song title. */
    val searchResults: StateFlow<SearchResults> = combine(library, _query) { lib, q ->
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) SearchResults()
        else SearchResults(
            artists = lib.artists.filter { needle in it.name.lowercase() },
            albums = lib.albums.filter { needle in it.title.lowercase() },
            songs = lib.tracks.filter { needle in it.title.lowercase() },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults())

    private val history = container.database.history().observeAll()

    private val artistTags = container.database.artistInfo().observeAll()
        // One- and two-letter tags are country codes and the like ("be", "uk"), not genres.
        .map { list -> list.associate { info -> info.name to info.tagList.filter { it.length > 2 } } }

    /**
     * Genres for the Genres tab: each track's file genre, else its artist's top MusicBrainz tag.
     * An album is listed under every genre one of its songs has, holding only that genre's songs,
     * so no genre is left without albums.
     */
    val genres: StateFlow<List<Genre>> = combine(library, artistTags) { lib, tags ->
        val keyOf = lib.tracks.associate { it.id to (effectiveGenreKey(it, tags) ?: UNKNOWN_GENRE) }
        val albumsByGenre = HashMap<String, MutableList<Album>>()
        for (album in lib.albums) {
            album.tracks.groupBy { keyOf.getValue(it.id) }.forEach { (key, tracks) ->
                albumsByGenre.getOrPut(key) { mutableListOf() } += if (tracks.size == album.tracks.size) album else album.copy(tracks = tracks)
            }
        }
        lib.tracks.groupBy { keyOf.getValue(it.id) }.map { (key, tracks) ->
            val name = if (key == UNKNOWN_GENRE) "Unknown genre"
            else tracks.mapNotNull { it.genre }.filter { it.lowercase() == key }.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: key.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) }
            Genre(
                key = key,
                name = name,
                albums = albumsByGenre[key].orEmpty(),
                songCount = tracks.size,
                artistCount = tracks.map { it.albumArtist ?: it.artist }.distinct().size,
            )
        }.sortedWith(compareBy({ it.key == UNKNOWN_GENRE }, { it.name.lowercase() }))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<Playlists> = combine(history, library, container.database.favourites().observeAll()) { history, lib, favourites ->
        val byId = lib.tracks.associateBy { it.id }
        val albumsByKey = lib.albums.associateBy { it.key }
        val stats = ListeningStats(history, System.currentTimeMillis())
        Playlists(
            recentTracks = history.asSequence().map { it.trackId }.distinct().mapNotNull { byId[it] }.take(RECENT_TRACKS).toList(),
            recentAlbums = history.asSequence().mapNotNull { byId[it.trackId]?.albumKey }.distinct()
                .mapNotNull { albumsByKey[it] }.take(RECENT_ALBUMS).toList(),
            mostPlayed = mostPlayed(byId, stats),
            favourites = favourites.mapNotNull { byId[it.trackId] },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Playlists())

    /** Generated mixes for the Home tab. */
    val mixes: StateFlow<List<Mix>> = combine(
        library, history, artistTags, container.database.favourites().observeAll(),
    ) { lib, history, tags, favourites ->
        // Recommendations are a nice-to-have: a bug there must never take the app down.
        runCatching { Recommender(lib.tracks, history, tags, favourites = favourites.mapTo(HashSet()) { it.trackId }).mixes() }
            .onFailure { Log.w(TAG, "Recommender failed", it) }
            .getOrDefault(emptyList())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val replayPeriod = MutableStateFlow<ReplayPeriod?>(null)

    val replay: StateFlow<Replay?> = combine(library, history, replayPeriod) { lib, history, period ->
        replay(history, lib.tracks.associateBy { it.id }, period)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    /**
     * Starts the Drive OAuth flow. If Google needs the user to pick an account or consent,
     * [launchConsent] receives the PendingIntent to start; its result goes to [onDriveConsentResult].
     */
    fun connectDrive(launchConsent: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            try {
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
        _driveMessage.value = null
        // Nothing is imported until the user picks folders (unless some are already chosen).
        if (settings.driveFolders.value.isNotEmpty()) viewModelScope.launch { repo.refreshDrive() }
    }

    fun refreshDrive() = viewModelScope.launch { repo.refreshDrive() }

    fun disconnectDrive() = viewModelScope.launch { repo.removeDrive() }

    /** Counted plays (half listened) per track id, for the artist page's top songs. */
    val playCounts: StateFlow<Map<String, Int>> = history
        .map { list -> list.filter { it.counted }.groupingBy { it.trackId }.eachCount() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Artist photo URLs by lower-cased name, for the Artists tab thumbnails. */
    val artistImageUrls: StateFlow<Map<String, String>> = container.artistImages.urls
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Fetches the photos of every artist not looked up yet (the Artists tab was opened). */
    fun prefetchArtistImages() = container.artistImages.prefetch(library.value.artists.map { it.name }, container.appScope)

    /** The artist's photo (looked up online once, then remembered), or null. */
    suspend fun artistImage(name: String): String? = container.artistImages.imageFor(name)

    // ---- User playlists ----

    private val playlistDao = container.database.playlists()

    val userPlaylists: StateFlow<List<PlaylistView>> = combine(
        library, playlistDao.observePlaylists(), playlistDao.observeEntries(),
    ) { lib, playlists, entries ->
        val byId = lib.tracks.associateBy { it.id }
        val songs = entries.groupBy({ it.playlistId }, { it.trackId })
        playlists.map { p -> PlaylistView(p.id, p.name, songs[p.id].orEmpty().mapNotNull { byId[it] }) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    fun importScrobbles(everything: Boolean) = importer.import(everything)
    fun importLoved() = importer.importLoved()

    init {
        // Songs added to the library may be ones Last.fm scrobbles are waiting for.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            repo.tracks.map { it.size }.distinctUntilChanged().debounce(30_000).collect { importer.rematch() }
        }
    }

    fun setOnlineMetadata(enabled: Boolean) {
        settings.setOnlineMetadata(enabled)
        if (enabled) repo.enrich()
    }

    fun retryMetadata() = repo.retryIncompleteMetadata()

    private fun buildLibrary(tracks: List<Track>): Library {
        val albums = tracks.groupBy { it.albumKey }.map { (key, list) ->
            val sorted = list.sortedWith(albumTrackOrder)
            val first = sorted.first()
            Album(
                key = key,
                title = first.album ?: "Unknown album",
                artist = first.albumArtist ?: first.artist ?: "Unknown artist",
                year = sorted.firstNotNullOfOrNull { it.year },
                artworkUri = sorted.firstNotNullOfOrNull { it.artworkUri },
                tracks = sorted,
            )
        }.sortedWith(compareBy({ it.title == "Unknown album" }, { it.title.lowercase() }))

        val artists = tracks.groupBy { it.albumArtist ?: it.artist ?: "Unknown artist" }.map { (name, list) ->
            Artist(
                name = name,
                tracks = list.sortedWith(compareBy<Track> { (it.album ?: "").lowercase() }.then(albumTrackOrder)),
                albumCount = list.map { it.albumKey }.distinct().size,
            )
        }.sortedWith(compareBy({ it.name == "Unknown artist" }, { it.name.lowercase() }))

        return Library(tracks, albums, artists)
    }

    private companion object {
        const val TAG = "LibraryViewModel"
        const val RECENT_TRACKS = 30
        const val RECENT_ALBUMS = 10
    }
}
