package com.kike.musicplayer.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.kike.musicplayer.R
import com.kike.musicplayer.data.lastfm.LastFm
import com.kike.musicplayer.data.lastfm.ScrobbleImporter
import com.kike.musicplayer.data.subsonic.ServerCollections
import com.kike.musicplayer.data.subsonic.SubsonicAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Sync card: whether it's on, when it last ran and what it did. */
data class AutoSyncState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val lastRun: Long = 0,
    val summary: String? = null,
)

/**
 * Brings listening history, favourites, playlists and loved tracks in step with the connected
 * services in one go, by itself every 24 hours when switched on (off by default), or when asked:
 * Last.fm "Import new" (plays), Last.fm loved tracks (favourites), then Navidrome's favourites and
 * playlists (last, so favourites just imported are starred there too). Services that aren't
 * connected are skipped. Checked at start and every hour while the app runs.
 */
class AutoSync(
    private val context: Context,
    private val lastFm: LastFm,
    private val importer: ScrobbleImporter,
    private val navidrome: SubsonicAccount,
    private val collections: ServerCollections,
    private val scope: CoroutineScope,
    private val isCasting: () -> Boolean = { false },
    prefsName: String = "autosync",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var consecutiveFailures = 0

    private val _state = MutableStateFlow(
        AutoSyncState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            lastRun = prefs.getLong(KEY_LAST_RUN, 0),
            summary = prefs.getString(KEY_SUMMARY, null),
        )
    )
    val state: StateFlow<AutoSyncState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            while (true) {
                if (due() && !isCasting()) {
                    if (NetworkUtils.isOnline(context) && consecutiveFailures < MAX_RETRIES) {
                        runAll(interactive = false)
                    }
                }
                delay(CHECK_EVERY_MS)
            }
        }
        scope.launch {
            NetworkUtils.observeOnline(context).collect { online ->
                if (online && due() && !isCasting() && consecutiveFailures < MAX_RETRIES) {
                    runAll(interactive = false)
                }
            }
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _state.value = _state.value.copy(enabled = on)
        if (on && due()) {
            consecutiveFailures = 0
            scope.launch { runAll(interactive = false) }
        }
    }

    /** Runs everything now (the card's button). */
    fun syncNow() {
        consecutiveFailures = 0
        scope.launch { runAll(interactive = true) }
    }

    private fun due() = _state.value.enabled && System.currentTimeMillis() - _state.value.lastRun >= INTERVAL_MS

    private suspend fun runAll(interactive: Boolean) {
        if (!NetworkUtils.isOnline(context)) {
            if (interactive) {
                _state.value = _state.value.copy(
                    summary = context.getString(R.string.network_error_offline)
                )
            }
            return
        }
        if (mutex.isLocked) return
        mutex.withLock {
            _state.value = _state.value.copy(running = true)
            val parts = ArrayList<String>()
            val result = runCatching {
                if (lastFm.state.value.user != null) {
                    importer.importAndWait(everything = false)
                    importer.status.value.text?.let { parts += "Last.fm history: $it" }
                    importer.importLovedAndWait()
                    importer.status.value.text?.let { parts += "Last.fm loved tracks: $it" }
                }
                if (navidrome.login() != null) {
                    val before = collections.status.value.lastChangesAt
                    collections.syncAndWait()
                    val s = collections.status.value
                    parts += "Navidrome: " + when {
                        s.failed -> s.message ?: "sync failed"
                        s.lastChangesAt > before -> s.lastChanges.orEmpty()
                        else -> "favourites and playlists up to date"
                    }
                }
            }
            result.onSuccess {
                consecutiveFailures = 0
            }.onFailure {
                consecutiveFailures++
                Log.w(TAG, "Sync failed (attempt $consecutiveFailures/$MAX_RETRIES)", it)
                parts += "Stopped: ${NetworkUtils.describeError(context, it)}"
            }
            val now = System.currentTimeMillis()
            val summary = parts.joinToString("\n").ifEmpty { "Nothing connected to sync with" }
            if (result.isSuccess) {
                prefs.edit { putLong(KEY_LAST_RUN, now); putString(KEY_SUMMARY, summary) }
                _state.value = _state.value.copy(running = false, lastRun = now, summary = summary)
            } else {
                _state.value = _state.value.copy(running = false, summary = summary)
            }
        }
    }

    private companion object {
        const val TAG = "AutoSync"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_RUN = "last_run"
        const val KEY_SUMMARY = "summary"
        const val INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val CHECK_EVERY_MS = 60 * 60 * 1000L
        const val MAX_RETRIES = 3
    }
}
