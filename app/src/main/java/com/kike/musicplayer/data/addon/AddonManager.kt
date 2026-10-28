package com.kike.musicplayer.data.addon

import android.content.Context
import androidx.core.content.edit
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages installed music add-on providers and their user-defined priority order.
 * Persists the ordered list to SharedPreferences and handles graceful fallback.
 */
class AddonManager(
    private val context: Context,
    val client: AddonClient,
    moshi: Moshi,
    prefsName: String = "addons",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val listAdapter = moshi.adapter<List<AddonInstance>>(
        Types.newParameterizedType(List::class.java, AddonInstance::class.java)
    )

    private val _addons = MutableStateFlow<List<AddonInstance>>(loadStoredAddons())
    val addons: StateFlow<List<AddonInstance>> = _addons.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Automatic one-time migration from legacy single "nebula" prefs if addons list is empty
        if (_addons.value.isEmpty()) {
            migrateLegacyNebula()
        }
    }

    private fun loadStoredAddons(): List<AddonInstance> {
        val json = prefs.getString(KEY_ADDONS_JSON, null) ?: return emptyList()
        return runCatching { listAdapter.fromJson(json) }.getOrNull().orEmpty()
    }

    private fun saveAddons(list: List<AddonInstance>) {
        _addons.value = list
        prefs.edit {
            putString(KEY_ADDONS_JSON, listAdapter.toJson(list))
        }
    }

    private fun migrateLegacyNebula() {
        val legacyPrefs = context.getSharedPreferences("nebula", Context.MODE_PRIVATE)
        val url = legacyPrefs.getString("manifest_url", null) ?: return
        val name = legacyPrefs.getString("addon_name", "Nebula") ?: "Nebula"
        val icon = legacyPrefs.getString("icon", null)
        val quality = legacyPrefs.getString("quality", "auto") ?: "auto"
        val migrated = AddonInstance(
            id = "nebula",
            name = name,
            manifestUrl = url,
            icon = icon,
            quality = quality,
            enabled = true,
        )
        saveAddons(listOf(migrated))
    }

    /**
     * Returns the active, enabled add-ons in user priority order.
     */
    fun addonsInOrder(): List<AddonInstance> = _addons.value.filter { it.enabled }

    /**
     * Validates and connects an add-on manifest URL.
     */
    suspend fun connect(manifestUrl: String, preferredId: String? = null): Result<AddonInstance> = withContext(Dispatchers.IO) {
        val trimmed = manifestUrl.trim()
        val targetUrl = if (trimmed.endsWith("/manifest.json", ignoreCase = true)) trimmed
        else trimmed.trimEnd('/') + "/manifest.json"

        _busy.value = true
        _message.value = null

        val result = client.validate(targetUrl)
        result.fold(
            onSuccess = { manifest ->
                val detectedId = preferredId
                    ?: when {
                        targetUrl.contains("nullbeat", ignoreCase = true) || manifest.name?.contains("nullbeat", ignoreCase = true) == true -> "nullbeat"
                        targetUrl.contains("nebula", ignoreCase = true) || manifest.name?.contains("nebula", ignoreCase = true) == true -> "nebula"
                        !manifest.id.isNullOrBlank() -> manifest.id.lowercase().replace("[^a-z0-9_-]".toRegex(), "")
                        else -> "addon_${System.currentTimeMillis()}"
                    }

                val name = manifest.name ?: (if (detectedId == "nullbeat") "NullBeat" else "Add-on")
                val instance = AddonInstance(
                    id = detectedId,
                    name = name,
                    manifestUrl = targetUrl,
                    icon = manifest.icon,
                    quality = "auto",
                    enabled = true,
                )

                val current = _addons.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.id == detectedId }
                if (existingIndex >= 0) {
                    current[existingIndex] = instance
                } else {
                    current.add(instance)
                }
                saveAddons(current)

                _busy.value = false
                _message.value = null
                Result.success(instance)
            },
            onFailure = { error ->
                _busy.value = false
                val msg = error.message ?: "Failed to connect to add-on manifest"
                _message.value = msg
                Result.failure(error)
            }
        )
    }

    /**
     * Disconnects and removes an installed add-on.
     */
    fun disconnect(addonId: String) {
        val updated = _addons.value.filterNot { it.id == addonId }
        saveAddons(updated)
    }

    /**
     * Moves an add-on from [fromIndex] to [toIndex] in the priority list.
     */
    fun reorder(fromIndex: Int, toIndex: Int) {
        val current = _addons.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            saveAddons(current)
        }
    }

    /**
     * Toggles an add-on's enabled status without removing it.
     */
    fun setEnabled(addonId: String, enabled: Boolean) {
        val updated = _addons.value.map {
            if (it.id == addonId) it.copy(enabled = enabled) else it
        }
        saveAddons(updated)
    }

    companion object {
        private const val KEY_ADDONS_JSON = "addons_json"
    }
}
