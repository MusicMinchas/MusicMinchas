package com.kike.musicplayer.data.nebula

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages the user's configured Nebula add-on manifest URL and its connection state.
 */
class NebulaAccount(
    context: Context,
    val client: NebulaClient,
    prefsName: String = "nebula",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadStoredState())
    val state: StateFlow<NebulaState> = _state.asStateFlow()

    private fun loadStoredState(): NebulaState {
        val url = prefs.getString(KEY_MANIFEST_URL, null)
        val name = prefs.getString(KEY_ADDON_NAME, null)
        val icon = prefs.getString(KEY_ICON, null)
        val quality = prefs.getString(KEY_QUALITY, "auto") ?: "auto"
        return NebulaState(
            manifestUrl = url,
            addonName = name,
            icon = icon,
            quality = quality,
        )
    }

    /**
     * Returns the base API endpoint (manifest URL without `/manifest.json`), or null if not connected.
     */
    fun baseUrl(): String? {
        val manifest = state.value.manifestUrl ?: return null
        return manifest.split("/manifest.json")[0].trimEnd('/')
    }

    fun isConnected(): Boolean = state.value.manifestUrl != null

    /**
     * Tests and connects a new manifest URL.
     */
    suspend fun connect(manifestUrl: String): Result<NebulaManifest> = withContext(Dispatchers.IO) {
        val trimmed = manifestUrl.trim()
        val targetUrl = if (trimmed.endsWith("/manifest.json", ignoreCase = true)) trimmed
        else trimmed.trimEnd('/') + "/manifest.json"

        _state.value = _state.value.copy(busy = true, message = null)
        val result = client.validate(targetUrl)
        result.onSuccess { manifest ->
            val name = manifest.name ?: "Nebula Add-on"
            val icon = manifest.icon
            prefs.edit {
                putString(KEY_MANIFEST_URL, targetUrl)
                putString(KEY_ADDON_NAME, name)
                putString(KEY_ICON, icon)
            }
            _state.value = NebulaState(
                manifestUrl = targetUrl,
                addonName = name,
                icon = icon,
                quality = "auto",
                busy = false,
                message = null,
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                busy = false,
                message = error.message ?: "Failed to connect to Nebula manifest",
            )
        }
        result
    }

    /**
     * Clears credentials and disconnects the add-on.
     */
    fun disconnect() {
        prefs.edit {
            remove(KEY_MANIFEST_URL)
            remove(KEY_ADDON_NAME)
            remove(KEY_ICON)
        }
        _state.value = NebulaState()
    }

    companion object {
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_ADDON_NAME = "addon_name"
        private const val KEY_ICON = "icon"
        private const val KEY_QUALITY = "quality"
    }
}
