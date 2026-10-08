package com.kike.musicplayer.data

import android.content.Context
import androidx.core.content.edit
import com.kike.musicplayer.data.drive.DriveFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _safFolders = MutableStateFlow(prefs.getStringSet(KEY_SAF, emptySet())!!.toSet())
    val safFolders: StateFlow<Set<String>> = _safFolders.asStateFlow()

    private val _driveFolders = MutableStateFlow(
        prefs.getStringSet(KEY_DRIVE_FOLDERS, emptySet())!!
            .mapNotNull { entry -> entry.split(SEP, limit = 2).takeIf { it.size == 2 }?.let { DriveFolder(it[0], it[1]) } }
            .sortedBy { it.name.lowercase() }
    )
    /** Drive folders to import, with a display path as the name. */
    val driveFolders: StateFlow<List<DriveFolder>> = _driveFolders.asStateFlow()

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

    fun setOnlineMetadata(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ONLINE, enabled) }
        _onlineMetadata.value = enabled
    }

    private companion object {
        const val KEY_SAF = "saf_folders"
        const val KEY_ONLINE = "online_metadata"
        const val KEY_DRIVE = "drive_connected"
        const val KEY_DRIVE_ACCOUNT = "drive_account"
        const val KEY_DRIVE_FOLDERS = "drive_folders"
        const val SEP = "\u001F"
    }
}
