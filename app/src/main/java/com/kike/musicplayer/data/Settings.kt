package com.kike.musicplayer.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.edit
import androidx.annotation.StringRes
import com.kike.musicplayer.R
import com.kike.musicplayer.data.drive.DriveFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DriveSyncMode(@StringRes val labelRes: Int, val label: String) {
    OFF(R.string.sync_mode_off, "No automatic sync"),
    DAILY(R.string.sync_mode_daily, "Every 24 h"),
    APP_START(R.string.sync_mode_app_start, "At app start"),
}

class Settings(private val context: Context, name: String = "settings") {
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

    private val _metaDeezer = MutableStateFlow(prefs.getBoolean(KEY_META_DEEZER, true))
    val metaDeezer: StateFlow<Boolean> = _metaDeezer.asStateFlow()

    private val _metaApple = MutableStateFlow(prefs.getBoolean(KEY_META_APPLE, true))
    val metaApple: StateFlow<Boolean> = _metaApple.asStateFlow()

    private val _metaMusicBrainz = MutableStateFlow(
        if (prefs.contains(KEY_META_MB)) prefs.getBoolean(KEY_META_MB, true)
        else prefs.getBoolean(KEY_ONLINE, true)
    )
    val metaMusicBrainz: StateFlow<Boolean> = _metaMusicBrainz.asStateFlow()

    private val _onlineMetadata = MutableStateFlow(
        _metaDeezer.value || _metaApple.value || _metaMusicBrainz.value
    )
    val onlineMetadata: StateFlow<Boolean> = _onlineMetadata.asStateFlow()

    private val _catalogEnabled = MutableStateFlow(prefs.getBoolean(KEY_CATALOG_ENABLED, false))
    /** Whether the Deezer & Apple Music catalog source is enabled in Music sources. */
    val catalogEnabled: StateFlow<Boolean> = _catalogEnabled.asStateFlow()

    private val _catalogShown = MutableStateFlow(prefs.getBoolean(KEY_CATALOG_SHOWN, true))
    /** Whether Deezer / Apple Music is ticked in the top-bar source selector. */
    val catalogShown: StateFlow<Boolean> = _catalogShown.asStateFlow()

    fun setCatalogEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_CATALOG_ENABLED, enabled)
            if (enabled) putBoolean(KEY_CATALOG_SHOWN, true)
        }
        _catalogEnabled.value = enabled
        if (enabled) _catalogShown.value = true
    }

    fun setCatalogShown(shown: Boolean) {
        prefs.edit { putBoolean(KEY_CATALOG_SHOWN, shown) }
        _catalogShown.value = shown
    }

    fun toggleCatalogShown() = setCatalogShown(!_catalogShown.value)

    private val _deezerHomeEnrichment = MutableStateFlow(prefs.getBoolean(KEY_DEEZER_HOME_ENRICHMENT, false))
    /** Whether public Deezer charts and curated playlists enrich the Home tab. */
    val deezerHomeEnrichment: StateFlow<Boolean> = _deezerHomeEnrichment.asStateFlow()

    fun setDeezerHomeEnrichment(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DEEZER_HOME_ENRICHMENT, enabled) }
        _deezerHomeEnrichment.value = enabled
    }

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

    /** Done once (1.31): songs MusicBrainz had named got their own tags read again. */
    var musicBrainzTitlesReread: Boolean
        get() = prefs.getBoolean(KEY_MB_TITLES_REREAD, false)
        set(value) = prefs.edit { putBoolean(KEY_MB_TITLES_REREAD, value) }

    /** When the Discover tab last fetched everything. */
    var discoverRefreshedAt: Long
        get() = prefs.getLong(KEY_DISCOVER_REFRESHED, 0L)
        set(value) = prefs.edit { putLong(KEY_DISCOVER_REFRESHED, value) }

    private fun refreshOnlineFlag() {
        val any = _metaDeezer.value || _metaApple.value || _metaMusicBrainz.value
        prefs.edit { putBoolean(KEY_ONLINE, any) }
        _onlineMetadata.value = any
    }

    fun setMetaDeezer(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_META_DEEZER, enabled) }
        _metaDeezer.value = enabled
        refreshOnlineFlag()
    }

    fun setMetaApple(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_META_APPLE, enabled) }
        _metaApple.value = enabled
        refreshOnlineFlag()
    }

    fun setMetaMusicBrainz(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_META_MB, enabled) }
        _metaMusicBrainz.value = enabled
        refreshOnlineFlag()
    }

    fun setOnlineMetadata(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_ONLINE, enabled)
            putBoolean(KEY_META_DEEZER, enabled)
            putBoolean(KEY_META_APPLE, enabled)
            putBoolean(KEY_META_MB, enabled)
        }
        _metaDeezer.value = enabled
        _metaApple.value = enabled
        _metaMusicBrainz.value = enabled
        _onlineMetadata.value = enabled
    }

    private val _darkMode = MutableStateFlow(prefs.getBoolean(KEY_DARK_MODE, false))
    /** Appearance: the dark theme instead of the light one. */
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DARK_MODE, enabled) }
        _darkMode.value = enabled
    }

    private val _accentColor = MutableStateFlow(prefs.getString(KEY_ACCENT_COLOR, "red") ?: "red")
    /** Appearance: accent color ("red" or "blue"). */
    val accentColor: StateFlow<String> = _accentColor.asStateFlow()

    fun setAccentColor(color: String) {
        val value = if (color.equals("blue", ignoreCase = true)) "blue" else "red"
        prefs.edit { putString(KEY_ACCENT_COLOR, value) }
        _accentColor.value = value
    }

    private val _fullScreenCover = MutableStateFlow(prefs.getBoolean(KEY_FULL_COVER, false))
    /** Appearance: Now Playing's cover across the whole width, up to the top of the screen. */
    val fullScreenCover: StateFlow<Boolean> = _fullScreenCover.asStateFlow()

    fun setFullScreenCover(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FULL_COVER, enabled) }
        _fullScreenCover.value = enabled
    }

    private val _sonosDownsample = MutableStateFlow(prefs.getBoolean(KEY_SONOS_DOWNSAMPLE, false))
    /** Sonos playback: whether to downsample hi-res (> 48 kHz) FLAC/ALAC to 48 or 44.1 kHz / 24-bit on the fly. */
    val sonosDownsample: StateFlow<Boolean> = _sonosDownsample.asStateFlow()

    fun setSonosDownsample(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SONOS_DOWNSAMPLE, enabled) }
        _sonosDownsample.value = enabled
    }

    private val _language = MutableStateFlow(getAppLanguage())
    /** App display language: "system", "en", or "es". */
    val language: StateFlow<String> = _language.asStateFlow()

    fun getAppLanguage(): String {
        val saved = prefs.getString(KEY_LANGUAGE, null)
        if (saved != null) return saved
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val locales = localeManager?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                val tag = locales[0].toLanguageTag().lowercase()
                return when {
                    tag.startsWith("es") -> "es"
                    tag.startsWith("en") -> "en"
                    else -> "system"
                }
            }
        }
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return "system"
        val tag = locales.toLanguageTags().lowercase()
        return when {
            tag.startsWith("es") -> "es"
            tag.startsWith("en") -> "en"
            else -> "system"
        }
    }

    fun setLanguage(tag: String) {
        prefs.edit { putString(KEY_LANGUAGE, tag) }
        _language.value = tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                localeManager.applicationLocales = if (tag == "system") {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(tag)
                }
            }
        }
        val localeList = if (tag == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    private companion object {
        const val KEY_LANGUAGE = "app_language"
        const val KEY_SAF = "saf_folders"
        const val KEY_HIDDEN_SCOPES = "hidden_scopes"
        const val KEY_INDEX_VERSION = "index_version"
        const val KEY_SERVER_SYNC_MODE = "server_sync_mode"
        const val KEY_LAST_SERVER_SYNC = "server_last_sync"
        const val KEY_SERVER_SCAN = "server_scan_synced"
        const val KEY_CONCERT_COUNTRY = "concert_country"
        const val KEY_DISCOVER_REFRESHED = "discover_refreshed_at"
        const val KEY_ONLINE = "online_metadata"
        const val KEY_META_DEEZER = "meta_deezer"
        const val KEY_META_APPLE = "meta_apple"
        const val KEY_META_MB = "meta_musicbrainz"
        const val KEY_CATALOG_ENABLED = "catalog_enabled"
        const val KEY_CATALOG_SHOWN = "catalog_shown"
        const val KEY_DEEZER_HOME_ENRICHMENT = "deezer_home_enrichment"
        const val KEY_DARK_MODE = "dark_mode"

        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_MB_TITLES_REREAD = "mb_titles_reread"
        const val KEY_FULL_COVER = "full_screen_cover"
        const val KEY_SONOS_DOWNSAMPLE = "sonos_downsample"
        const val KEY_SYNC_MODE = "drive_sync_mode"
        const val KEY_LAST_SYNC = "drive_last_sync"
        const val KEY_DRIVE = "drive_connected"
        const val KEY_DRIVE_ACCOUNT = "drive_account"
        const val KEY_DRIVE_FOLDERS = "drive_folders"
        const val SEP = "\u001F"
    }
}
