package com.kike.musicplayer.data

import android.content.Context
import androidx.core.content.edit
import com.kike.musicplayer.data.drive.DriveFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DriveSyncMode(val label: String) {
    OFF("No automatic sync"),
    DAILY("Every 24 h"),
    APP_START("At app start"),
}

class Settings(context: Context, name: String = "settings") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val _safFolders = MutableStateFlow(prefs.getStringSet(KEY_SAF, emptySet())!!.toSet())
    val safFolders: StateFlow<Set<String>> = _safFolders.asStateFlow()

    private val _driveFolders = MutableStateFlow(
        prefs.getStringSet(KEY_DRIVE_FOLDERS, emptySet())!!
            .mapNotNull { entry -> entry.split(SEP, limit = 2).takeIf { it.size == 2 }?.let { DriveFolder(it[0], it[1]) } }
            .sortedBy { it.name.lowercase() }
    )
    /** Drive folders to import, with a display path as the name. */
    val driveFolders: StateFlow<List<DriveFolder>> = _driveFolders.asStateFlow()

    private val _syncMode = MutableStateFlow(
        runCatching { DriveSyncMode.valueOf(prefs.getString(KEY_SYNC_MODE, null) ?: "") }.getOrDefault(DriveSyncMode.DAILY)
    )
    /** When the app re-lists the chosen Drive folders by itself. */
    val syncMode: StateFlow<DriveSyncMode> = _syncMode.asStateFlow()

    private val _lastDriveSync = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L))
    /** Time of the last successful Drive sync (0 = never). */
    val lastDriveSync: StateFlow<Long> = _lastDriveSync.asStateFlow()

    fun setSyncMode(mode: DriveSyncMode) {
        prefs.edit { putString(KEY_SYNC_MODE, mode.name) }
        _syncMode.value = mode
    }

    fun setLastDriveSync(time: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, time) }
        _lastDriveSync.value = time
    }

    private val _onlineMetadata = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, true))
    val onlineMetadata: StateFlow<Boolean> = _onlineMetadata.asStateFlow()

    var driveAccount: String?
        get() = prefs.getString(KEY_DRIVE_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_DRIVE_ACCOUNT, value) }

    var driveConnected: Boolean
        get() = prefs.getBoolean(KEY_DRIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_DRIVE, value) }

    fun addSafFolder(uri: String) = setSafFolders(_safFolders.value + uri)
    fun removeSafFolder(uri: String) = setSafFolders(_safFolders.value - uri)

    private fun setSafFolders(folders: Set<String>) {
        prefs.edit { putStringSet(KEY_SAF, folders) }
        _safFolders.value = folders
    }

    fun addDriveFolder(folder: DriveFolder) =
        setDriveFolders(_driveFolders.value.filter { it.id != folder.id } + folder)

    fun removeDriveFolder(id: String) = setDriveFolders(_driveFolders.value.filter { it.id != id })

    fun clearDriveFolders() = setDriveFolders(emptyList())

    private fun setDriveFolders(folders: List<DriveFolder>) {
        prefs.edit { putStringSet(KEY_DRIVE_FOLDERS, folders.mapTo(HashSet()) { it.id + SEP + it.name }) }
        _driveFolders.value = folders.sortedBy { it.name.lowercase() }
    }

    private val _hiddenScopes = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN_SCOPES, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    )
    /** Sources (Track scopes) switched off in the library; a source added later starts switched on. */
    val hiddenScopes: StateFlow<Set<Int>> = _hiddenScopes.asStateFlow()

    fun setHiddenScopes(scopes: Set<Int>) {
        prefs.edit { putStringSet(KEY_HIDDEN_SCOPES, scopes.mapTo(HashSet()) { it.toString() }) }
        _hiddenScopes.value = scopes
    }

    private val _serverSyncMode = MutableStateFlow(
        runCatching { DriveSyncMode.valueOf(prefs.getString(KEY_SERVER_SYNC_MODE, null) ?: "") }.getOrDefault(DriveSyncMode.DAILY)
    )
    /** When the app lists the server's songs again by itself. */
    val serverSyncMode: StateFlow<DriveSyncMode> = _serverSyncMode.asStateFlow()

    fun setServerSyncMode(mode: DriveSyncMode) {
        prefs.edit { putString(KEY_SERVER_SYNC_MODE, mode.name) }
        _serverSyncMode.value = mode
    }

    private val _lastServerSync = MutableStateFlow(prefs.getLong(KEY_LAST_SERVER_SYNC, 0L))
    /** Time of the last complete server sync (0 = never). */
    val lastServerSync: StateFlow<Long> = _lastServerSync.asStateFlow()

    fun setLastServerSync(time: Long) {
        prefs.edit { putLong(KEY_LAST_SERVER_SYNC, time) }
        _lastServerSync.value = time
    }

    /** LibraryIndexer.VERSION the library was last indexed with (1 = database version 17/18's). */
    var indexVersion: Int
        get() = prefs.getInt(KEY_INDEX_VERSION, 1)
        set(value) = prefs.edit { putInt(KEY_INDEX_VERSION, value) }

    /** The server's last library scan when it was last synced: unchanged means nothing to sync. */
    var serverScanSynced: String?
        get() = prefs.getString(KEY_SERVER_SCAN, null)
        set(value) = prefs.edit { putString(KEY_SERVER_SCAN, value) }

    private val _concertCountry =MutableStateFlow(prefs.getString(KEY_CONCERT_COUNTRY, null) ?: "BE")
    /** ISO country code whose concerts the Discover tab lists. */
    val concertCountry: StateFlow<String> = _concertCountry.asStateFlow()

    fun setConcertCountry(code: String) {
        prefs.edit { putString(KEY_CONCERT_COUNTRY, code) }
        _concertCountry.value = code
    }

    /** When the Discover tab last fetched everything. */
    var discoverRefreshedAt: Long
        get() = prefs.getLong(KEY_DISCOVER_REFRESHED, 0L)
        set(value) = prefs.edit { putLong(KEY_DISCOVER_REFRESHED, value) }

    fun setOnlineMetadata(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ONLINE, enabled) }
        _onlineMetadata.value = enabled
    }

    private val _darkMode = MutableStateFlow(prefs.getBoolean(KEY_DARK_MODE, false))
    /** Appearance: the black and red theme instead of the light one. */
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DARK_MODE, enabled) }
        _darkMode.value = enabled
    }

    private val _fullScreenCover = MutableStateFlow(prefs.getBoolean(KEY_FULL_COVER, false))
    /** Appearance: Now Playing's cover across the whole width, up to the top of the screen. */
    val fullScreenCover: StateFlow<Boolean> = _fullScreenCover.asStateFlow()

    fun setFullScreenCover(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FULL_COVER, enabled) }
        _fullScreenCover.value = enabled
    }

    private companion object {
        const val KEY_SAF = "saf_folders"
        const val KEY_HIDDEN_SCOPES = "hidden_scopes"
        const val KEY_INDEX_VERSION = "index_version"
        const val KEY_SERVER_SYNC_MODE = "server_sync_mode"
        const val KEY_LAST_SERVER_SYNC = "server_last_sync"
        const val KEY_SERVER_SCAN = "server_scan_synced"
        const val KEY_CONCERT_COUNTRY = "concert_country"
        const val KEY_DISCOVER_REFRESHED = "discover_refreshed_at"
        const val KEY_ONLINE = "online_metadata"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_FULL_COVER = "full_screen_cover"
        const val KEY_SYNC_MODE = "drive_sync_mode"
        const val KEY_LAST_SYNC = "drive_last_sync"
        const val KEY_DRIVE = "drive_connected"
        const val KEY_DRIVE_ACCOUNT = "drive_account"
        const val KEY_DRIVE_FOLDERS = "drive_folders"
        const val SEP = "\u001F"
    }
}
