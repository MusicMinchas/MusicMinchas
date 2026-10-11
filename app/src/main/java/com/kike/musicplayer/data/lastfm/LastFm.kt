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
class LastFm(context: Context, val client: LastFmClient) {
    private val prefs = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        LastFmState(user = prefs.getString(KEY_USER, null), awaitingApproval = prefs.getString(KEY_TOKEN, null) != null)
    )
    val state: StateFlow<LastFmState> = _state.asStateFlow()

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

    fun disconnect() {
        prefs.edit {
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
    }
}
