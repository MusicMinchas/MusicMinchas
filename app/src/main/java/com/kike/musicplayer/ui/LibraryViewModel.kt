package com.kike.musicplayer.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kike.musicplayer.container
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.drive.DriveFolder
import com.kike.musicplayer.playback.albumTrackOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    val searchResults: StateFlow<List<Track>> = combine(repo.tracks, _query) { tracks, q ->
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) tracks
        else tracks.filter { t -> listOfNotNull(t.title, t.artist, t.album, t.albumArtist).any { needle in it.lowercase() } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val scanStatus = repo.status
    val lastError = repo.lastError
    val metadataProgress = repo.metadataProgress
    val pendingMetadata = repo.pendingMetadata.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
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
    }
}
