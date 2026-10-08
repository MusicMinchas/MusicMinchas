package com.kike.musicplayer.sonos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Implemented by the playback service, which owns the session and swaps players. */
interface CastHost {
    fun startCast(group: SonosGroup)
    fun stopCast()
}

/** Speaker discovery and cast state, shared by the UI and the playback service. */
class CastManager(private val context: Context, private val scope: CoroutineScope) {
    private val _groups = MutableStateFlow<List<SonosGroup>>(emptyList())
    val groups: StateFlow<List<SonosGroup>> = _groups.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _active = MutableStateFlow<SonosGroup?>(null)
    /** The group currently playing the app's queue, or null when playing on the phone. */
    val active: StateFlow<SonosGroup?> = _active.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    var host: CastHost? = null

    private var discoverJob: Job? = null

    fun discover() {
        if (discoverJob?.isActive == true) return
        discoverJob = scope.launch {
            _discovering.value = true
            try {
                val found = withContext(Dispatchers.IO) {
                    val ips = Sonos.discoverSpeakerIps(context)
                    // Any speaker knows the whole topology; try them until one answers.
                    ips.firstNotNullOfOrNull { ip -> runCatching { Sonos.groups(ip) }.getOrNull() }.orEmpty()
                }
                _groups.value = found
                if (found.isEmpty()) _message.value = "No Sonos speakers found on this Wi-Fi network"
            } catch (e: Exception) {
                Log.w(TAG, "Discovery failed", e)
                _message.value = "Couldn't search for speakers: ${e.message}"
            } finally {
                _discovering.value = false
            }
        }
    }

    fun cast(group: SonosGroup) {
        _message.value = null
        val h = host ?: run {
            _message.value = "The player isn't running"
            return
        }
        h.startCast(group)
    }

    fun stopCast() {
        host?.stopCast()
    }

    // Called by the host.

    internal fun onCastStarted(group: SonosGroup) {
        _active.value = group
    }

    internal fun onCastEnded(message: String? = null) {
        _active.value = null
        message?.let { _message.value = it }
    }

    private companion object {
        const val TAG = "CastManager"
    }
}
