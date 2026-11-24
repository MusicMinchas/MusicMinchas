package com.kike.musicplayer.data.listenbrainz

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.kike.musicplayer.data.Secrets
import com.kike.musicplayer.data.lastfm.ScrobbleRule
import com.kike.musicplayer.data.lastfm.ScrobbleSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the ListenBrainz section of Settings -> Integrations shows. */
data class ListenBrainzState(
    /** Connected ListenBrainz username, or null. */
    val user: String? = null,
    /** Validating a pasted User Token. */
    val connecting: Boolean = false,
    val message: String? = null,
)

/**
 * The ListenBrainz account: each user connects with their personal User Token from
 * https://listenbrainz.org/settings/, encrypted at rest with an Android Keystore key.
 */
class ListenBrainz(
    context: Context,
    val client: ListenBrainzApiClient,
    prefsName: String = "listenbrainz",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    private val _state = MutableStateFlow(
        ListenBrainzState(user = prefs.getString(KEY_USER, null))
    )
    val state: StateFlow<ListenBrainzState> = _state.asStateFlow()

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

    /** Called once connected, e.g. to flush listens queued while disconnected. */
    var onConnected: (() -> Unit)? = null

    /** Decrypted User Token, or null when disconnected or unreadable. */
    val userToken: String?
        get() = cachedToken ?: prefs.getString(KEY_TOKEN_ENC, null)
            ?.let { Secrets.decrypt(it, Secrets.ALIAS_LISTENBRAINZ_TOKEN) }
            ?.also { cachedToken = it }

    /**
     * How many listens ListenBrainz had at the last import (-1: unknown), to notice listens
     * added with older dates (e.g. bulk imports) which an incremental pass misses.
     */
    var importedTotal: Int
        get() = prefs.getInt(KEY_IMPORTED_TOTAL, -1)
        set(value) = prefs.edit { putInt(KEY_IMPORTED_TOTAL, value) }

    /** Validates [rawToken] against ListenBrainz and connects the account on success. */
    suspend fun connect(rawToken: String): Boolean = mutex.withLock {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            _state.update { it.copy(message = "Enter your ListenBrainz user token") }
            return false
        }
        _state.update { it.copy(connecting = true, message = null) }
        try {
            val result = client.validateToken(token)
            if (!result.valid || result.userName.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        connecting = false,
                        message = result.message ?: "Invalid ListenBrainz user token",
                    )
                }
                return false
            }
            val encrypted = Secrets.encrypt(token, Secrets.ALIAS_LISTENBRAINZ_TOKEN)
            prefs.edit {
                putString(KEY_USER, result.userName)
                putString(KEY_TOKEN_ENC, encrypted)
            }
            cachedToken = token
            _state.value = ListenBrainzState(user = result.userName)
            onConnected?.invoke()
            true
        } catch (e: Exception) {
            Log.w(TAG, "validate-token failed", e)
            _state.update { it.copy(connecting = false, message = "Couldn't reach ListenBrainz: ${e.message}") }
            false
        }
    }

    fun disconnect() {
        cachedToken = null
        prefs.edit {
            remove(KEY_IMPORTED_TOTAL)
            remove(KEY_USER)
            remove(KEY_TOKEN_ENC)
        }
        _state.value = ListenBrainzState()
    }

    /** ListenBrainz rejected the token (HTTP 401): clear it while keeping outbox items for reconnect. */
    fun onTokenInvalid() {
        disconnect()
        _state.update {
            it.copy(message = "ListenBrainz token was rejected. Connect again with a valid token to keep scrobbling.")
        }
    }

    companion object {
        private const val TAG = "ListenBrainz"
        private const val KEY_USER = "user"
        private const val KEY_TOKEN_ENC = "token_enc"
        private const val KEY_SCROBBLE = "scrobble"
        private const val KEY_PERCENT = "scrobble_percent"
        private const val KEY_CAP = "scrobble_cap_minutes"
        private const val KEY_IMPORTED_TOTAL = "imported_total"
    }
}
