package com.kike.musicplayer.data.lastfm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether plays are scrobbled, and when. */
data class ScrobbleSettings(val enabled: Boolean = true, val rule: ScrobbleRule = ScrobbleRule())

/** What the Last.fm section of the settings shows. */
data class LastFmState(
    /** Connected Last.fm user, or null. */
    val user: String? = null,
    /** Sent to Last.fm's approval page; waiting for the user to come back. */
    val awaitingApproval: Boolean = false,
    val message: String? = null,
)

/**
 * The Last.fm account: connecting uses Last.fm's token flow (https://www.last.fm/api/desktopauth),
 * so the user signs in on Last.fm's own page and the app never sees the password. The session key
 * it gets doesn't expire unless the user revokes access on Last.fm.
 */
class LastFm(context: Context, val client: LastFmClient, prefsName: String = "lastfm") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        LastFmState(user = prefs.getString(KEY_USER, null), awaitingApproval = prefs.getString(KEY_TOKEN, null) != null)
    )
    val state: StateFlow<LastFmState> = _state.asStateFlow()

    private val _scrobbleSettings = MutableStateFlow(
        ScrobbleSettings(
            enabled = prefs.getBoolean(KEY_SCROBBLE, true),
            rule = runCatching {
                ScrobbleRule(
                    percent = prefs.getInt(KEY_PERCENT, ScrobbleRule.MIN_PERCENT),
                    capMinutes = prefs.getInt(KEY_CAP, ScrobbleRule.MIN_CAP_MINUTES).takeIf { it > 0 },
                )
            }.getOrDefault(ScrobbleRule()),
        )
    )
    val scrobbleSettings: StateFlow<ScrobbleSettings> = _scrobbleSettings.asStateFlow()

    fun setScrobbleSettings(settings: ScrobbleSettings) {
        prefs.edit {
            putBoolean(KEY_SCROBBLE, settings.enabled)
            putInt(KEY_PERCENT, settings.rule.percent)
            putInt(KEY_CAP, settings.rule.capMinutes ?: 0)
        }
        _scrobbleSettings.value = settings
    }

    /** Called once connected, e.g. to send scrobbles kept while signed out. */
    var onConnected: (() -> Unit)? = null

    /** Session key for signed calls, or null when not connected. */
    val sessionKey: String? get() = prefs.getString(KEY_SESSION, null)

    /** Starts connecting: returns the Last.fm page where the user approves this app. */
    suspend fun startConnect(): Uri? = mutex.withLock {
        if (!client.isConfigured) {
            _state.update { it.copy(message = "Last.fm isn't set up in this build (missing API key)") }
            return null
        }
        try {
            val token = client.parse<TokenJson>(client.call("auth.getToken", signed = true)).token
            prefs.edit { putString(KEY_TOKEN, token) }
            _state.update { it.copy(awaitingApproval = true, message = null) }
            Uri.parse(LastFmClient.AUTH_URL).buildUpon()
                .appendQueryParameter("api_key", client.apiKey)
                .appendQueryParameter("token", token)
                .appendQueryParameter("cb", CALLBACK)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "auth.getToken failed", e)
            _state.update { it.copy(message = "Couldn't reach Last.fm: ${e.message}") }
            null
        }
    }

    /**
     * Finishes connecting once the user approved the app on Last.fm (called when the app comes
     * back to the front, or from the callback link). Quietly keeps waiting if not approved yet.
     */
    suspend fun finishConnect(callbackToken: String? = null) = mutex.withLock {
        val token = callbackToken ?: prefs.getString(KEY_TOKEN, null) ?: return@withLock
        try {
            val session = client.parse<SessionJson>(client.call("auth.getSession", mapOf("token" to token), signed = true)).session
            prefs.edit {
                putString(KEY_USER, session.name)
                putString(KEY_SESSION, session.key)
                remove(KEY_TOKEN)
            }
            _state.value = LastFmState(user = session.name)
            onConnected?.invoke()
        } catch (e: LastFmException) {
            when (e.code) {
                LastFmException.TOKEN_NOT_AUTHORIZED -> Unit // not approved yet: keep waiting
                else -> {
                    // Expired (an hour) or otherwise unusable: start over.
                    prefs.edit { remove(KEY_TOKEN) }
                    _state.update { it.copy(awaitingApproval = false, message = "Connecting to Last.fm didn't finish. Try again.") }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "auth.getSession failed", e)
            _state.update { it.copy(message = "Couldn't reach Last.fm: ${e.message}") }
        }
    }

    fun cancelConnect() {
        prefs.edit { remove(KEY_TOKEN) }
        _state.update { it.copy(awaitingApproval = false, message = null) }
    }

    /**
     * How many scrobbles Last.fm had at the last import (-1: unknown), to notice scrobbles added
     * with older dates, e.g. by bulk-scrobbling tools, which an import of the newest ones misses.
     */
    var importedTotal: Int
        get() = prefs.getInt(KEY_IMPORTED_TOTAL, -1)
        set(value) = prefs.edit { putInt(KEY_IMPORTED_TOTAL, value) }

    fun disconnect() {
        prefs.edit {
            remove(KEY_IMPORTED_TOTAL)
            remove(KEY_USER)
            remove(KEY_SESSION)
            remove(KEY_TOKEN)
        }
        _state.value = LastFmState()
    }

    /** Last.fm rejected the session key (access revoked): forget it so the user can reconnect. */
    fun onSessionInvalid() {
        disconnect()
        _state.update { it.copy(message = "Last.fm access was revoked. Connect again to keep scrobbling.") }
    }

    private class TokenJson(val token: String)
    private class SessionJson(val session: Session)
    private class Session(val name: String, val key: String)

    companion object {
        private const val TAG = "LastFm"
        /** Where Last.fm sends the browser back after approval; MainActivity handles it. */
        const val CALLBACK = "musicminchas://lastfm"
        private const val KEY_USER = "user"
        private const val KEY_SESSION = "session_key"
        private const val KEY_TOKEN = "pending_token"
        private const val KEY_SCROBBLE = "scrobble"
        private const val KEY_PERCENT = "scrobble_percent"
        private const val KEY_CAP = "scrobble_cap_minutes"
        private const val KEY_IMPORTED_TOTAL = "imported_total"
    }
}
