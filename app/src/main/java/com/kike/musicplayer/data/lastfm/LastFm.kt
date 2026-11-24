package com.kike.musicplayer.data.lastfm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.kike.musicplayer.data.Secrets
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Whether plays are scrobbled, and when. */
data class ScrobbleSettings(val enabled: Boolean = true, val rule: ScrobbleRule = ScrobbleRule())

/** What the Last.fm section of the settings shows. */
data class LastFmState(
    /** Connected Last.fm user, or null. */
    val user: String? = null,
    /** Sent to Last.fm's approval page; waiting for the user to come back. */
    val awaitingApproval: Boolean = false,
    /** User-provided Last.fm Pro API key (empty when none configured). */
    val apiKey: String = "",
    /** Whether both a user-provided API key and encrypted shared secret are saved. */
    val hasCredentials: Boolean = false,
    val message: String? = null,
)

/**
 * The Last.fm account: each user configures their own Last.fm Pro API key and shared secret
 * (encrypted at rest via Android Keystore) and then connects via Last.fm's token flow
 * (https://www.last.fm/api/desktopauth), so the app never sees the Last.fm password.
 */
class LastFm(
    context: Context,
    http: OkHttpClient,
    moshi: Moshi,
    prefsName: String = "lastfm",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var cachedSecret: String? = null

    /** Saved user-provided Last.fm API key, or empty when not configured. */
    val apiKey: String get() = prefs.getString(KEY_API_KEY, null).orEmpty()

    /** Decrypted user-provided Last.fm shared secret, or empty when not configured/unreadable. */
    val sharedSecret: String
        get() = cachedSecret ?: prefs.getString(KEY_SECRET_ENC, null)
            ?.let { Secrets.decrypt(it, Secrets.ALIAS_LASTFM_SECRET) }
            ?.also { cachedSecret = it }
            .orEmpty()

    val hasCredentials: Boolean get() = apiKey.isNotEmpty() && sharedSecret.isNotEmpty()

    val client: LastFmClient = LastFmClient(http, moshi, { apiKey }, { sharedSecret })

    private val _state = MutableStateFlow(readState())
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

    /**
     * Saves the user's Last.fm Pro API key and shared secret. If credentials change (or if the
     * user previously signed in with an older bundled key), clears any stale session key so the
     * user signs in cleanly against the new API key.
     */
    fun saveCredentials(rawApiKey: String, rawSecret: String): Boolean {
        val key = rawApiKey.trim()
        val secret = rawSecret.trim()
        if (key.isEmpty() || secret.isEmpty()) {
            _state.update { it.copy(message = "Enter both your Last.fm Pro API key and shared secret") }
            return false
        }
        val changed = key != apiKey || secret != sharedSecret
        val encrypted = Secrets.encrypt(secret, Secrets.ALIAS_LASTFM_SECRET)
        prefs.edit {
            putString(KEY_API_KEY, key)
            putString(KEY_SECRET_ENC, encrypted)
            if (changed) {
                remove(KEY_USER)
                remove(KEY_SESSION)
                remove(KEY_TOKEN)
            }
        }
        cachedSecret = secret
        _state.value = readState()
        return true
    }

    /** Removes the saved API key, shared secret, and active Last.fm session. */
    fun clearCredentials() {
        cachedSecret = null
        prefs.edit {
            remove(KEY_API_KEY)
            remove(KEY_SECRET_ENC)
            remove(KEY_IMPORTED_TOTAL)
            remove(KEY_USER)
            remove(KEY_SESSION)
            remove(KEY_TOKEN)
        }
        _state.value = readState()
    }

    /** Starts connecting: returns the Last.fm page where the user approves this app. */
    suspend fun startConnect(): Uri? = mutex.withLock {
        if (!client.isConfigured) {
            _state.update { it.copy(message = "Enter your Last.fm Pro API key and shared secret first") }
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
        } catch (e: LastFmException) {
            Log.w(TAG, "auth.getToken failed", e)
            val msg = if (e.isAuthConfigError) {
                "Last.fm rejected your API key or shared secret (Last.fm Pro required): ${e.message}"
            } else {
                "Couldn't reach Last.fm: ${e.message}"
            }
            _state.update { it.copy(message = msg) }
            null
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
        if (!client.isConfigured) return@withLock
        try {
            val session = client.parse<SessionJson>(client.call("auth.getSession", mapOf("token" to token), signed = true)).session
            prefs.edit {
                putString(KEY_USER, session.name)
                putString(KEY_SESSION, session.key)
                remove(KEY_TOKEN)
            }
            _state.value = readState()
            onConnected?.invoke()
        } catch (e: LastFmException) {
            when {
                e.code == LastFmException.TOKEN_NOT_AUTHORIZED -> Unit // not approved yet: keep waiting
                e.isAuthConfigError -> {
                    prefs.edit { remove(KEY_TOKEN) }
                    _state.update {
                        it.copy(
                            awaitingApproval = false,
                            message = "Last.fm rejected your API key or shared secret (Last.fm Pro required): ${e.message}",
                        )
                    }
                }
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

    /** Signs out of Last.fm while preserving the user's saved API key & secret (if any). */
    fun disconnect() {
        prefs.edit {
            remove(KEY_IMPORTED_TOTAL)
            remove(KEY_USER)
            remove(KEY_SESSION)
            remove(KEY_TOKEN)
        }
        _state.value = readState()
    }

    /** Last.fm rejected the session key (access revoked): forget it so the user can reconnect. */
    fun onSessionInvalid() {
        disconnect()
        _state.update { it.copy(message = "Last.fm access was revoked. Connect again to keep scrobbling.") }
    }

    /**
     * Last.fm rejected the API key or signature (e.g. error 10/26 when Pro lapsed or key invalid).
     * Keeps queued outbox items intact so nothing is lost while the user fixes their key/Pro status.
     */
    fun onApiKeyRejected(detail: String?) {
        val reason = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        _state.update {
            it.copy(
                message = "Last.fm rejected your API key$reason. Pending scrobbles are kept until your Pro API key is valid again."
            )
        }
    }

    private fun readState() = LastFmState(
        user = prefs.getString(KEY_USER, null),
        awaitingApproval = prefs.getString(KEY_TOKEN, null) != null,
        apiKey = apiKey,
        hasCredentials = hasCredentials,
    )

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
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SECRET_ENC = "secret_enc"
        private const val KEY_SCROBBLE = "scrobble"
        private const val KEY_PERCENT = "scrobble_percent"
        private const val KEY_CAP = "scrobble_cap_minutes"
        private const val KEY_IMPORTED_TOTAL = "imported_total"
    }
}
