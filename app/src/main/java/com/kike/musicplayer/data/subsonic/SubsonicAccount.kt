package com.kike.musicplayer.data.subsonic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.kike.musicplayer.Bench
import com.kike.musicplayer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException

/** What the server section of the settings shows. */
data class SubsonicState(
    /** Server address, or null when no server is set up. */
    val address: String? = null,
    val username: String? = null,
    /** "Navidrome 0.61.1", or the Subsonic API version for servers that don't say. */
    val server: String? = null,
    /** Songs on the server, when the server says (Navidrome does). */
    val songCount: Long? = null,
    val folders: List<String> = emptyList(),
    /** When the server last answered. */
    val checkedAt: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * The Subsonic / Navidrome server account. The password is needed for every request (each one is
 * signed with a fresh salt), so it's kept encrypted with a key in the Android Keystore that never
 * leaves the phone; after a restore from backup the key is gone and the user signs in again.
 */
class SubsonicAccount(context: Context, val client: SubsonicClient, prefsName: String = "subsonic") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(stored())
    val state: StateFlow<SubsonicState> = _state.asStateFlow()

    /** How to sign in to the server, or null when none is set up (or the password can't be read). */
    fun login(): SubsonicLogin? = cachedLogin ?: readLogin()?.also { cachedLogin = it }

    /** Decrypting takes a Keystore call; covers ask for the login all the time. */
    @Volatile private var cachedLogin: SubsonicLogin? = null

    private fun readLogin(): SubsonicLogin? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.let(Secrets::decrypt) ?: return null
        return SubsonicLogin(address, user, password, prefs.getBoolean(KEY_PLAIN_AUTH, false))
    }

    /** Checks the address and sign-in, and saves them when the server accepts them. */
    suspend fun connect(address: String, username: String, password: String): Boolean = mutex.withLock {
        val base = SubsonicClient.normalizeAddress(address)
        when {
            base == null -> return fail("That doesn't look like a server address")
            username.isBlank() || password.isEmpty() -> return fail("Enter your user name and password")
        }
        _state.update { it.copy(busy = true, message = null) }
        var login = SubsonicLogin(base!!, username.trim(), password)
        val ping = try {
            try {
                client.ping(login)
            } catch (e: SubsonicException) {
                if (e.code != SubsonicException.TOKEN_AUTH_UNSUPPORTED) throw e
                login = login.copy(plainAuth = true)
                client.ping(login)
            }
        } catch (e: Exception) {
            return fail(describe(e))
        }
        prefs.edit {
            putString(KEY_ADDRESS, login.baseUrl)
            putString(KEY_USER, login.username)
            putString(KEY_PASSWORD, Secrets.encrypt(password))
            putBoolean(KEY_PLAIN_AUTH, login.plainAuth)
            putString(KEY_SERVER, serverName(ping))
        }
        cachedLogin = login
        _state.value = stored()
        refreshInfo(login)
        true
    }

    /** Asks the server again for its details (song count, folders), e.g. when the settings open. */
    suspend fun check() = mutex.withLock {
        val login = login()
        if (login == null) {
            if (prefs.contains(KEY_ADDRESS)) {
                _state.update { it.copy(message = "Sign in to your server again (the saved password can't be read)") }
            }
            return@withLock
        }
        _state.update { it.copy(busy = true) }
        try {
            prefs.edit { putString(KEY_SERVER, serverName(client.ping(login))) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, message = describe(e)) }
            return@withLock
        }
        refreshInfo(login)
    }

    private suspend fun refreshInfo(login: SubsonicLogin) {
        val folders = runCatching { client.musicFolders(login).map { it.name ?: it.id } }
            .onFailure { Log.w(TAG, "getMusicFolders failed", it) }.getOrNull()
        val scan = runCatching { client.scanStatus(login) }
            .onFailure { Log.w(TAG, "getScanStatus failed", it) }.getOrNull()
        if (BuildConfig.DEBUG) {
            // How long a sync would take: one page of the song listing.
            runCatching { Bench.time("subsonic search3 page of $SYNC_PAGE") { client.songs(login, 0, SYNC_PAGE) } }
                .onSuccess { Bench.log("subsonic page: ${it.size} songs, first ${it.firstOrNull()?.title}") }
                .onFailure { Log.w(TAG, "search3 failed", it) }
        }
        prefs.edit {
            folders?.let { putString(KEY_FOLDERS, it.joinToString(SEP)) }
            scan?.count?.let { putLong(KEY_SONGS, it) }
            putLong(KEY_CHECKED, System.currentTimeMillis())
        }
        _state.value = stored()
    }

    fun disconnect() {
        cachedLogin = null
        prefs.edit { clear() }
        _state.value = SubsonicState()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun fail(message: String): Boolean {
        _state.update { it.copy(busy = false, message = message) }
        return false
    }

    private fun stored() = SubsonicState(
        address = prefs.getString(KEY_ADDRESS, null),
        username = prefs.getString(KEY_USER, null),
        server = prefs.getString(KEY_SERVER, null),
        songCount = prefs.getLong(KEY_SONGS, -1).takeIf { it >= 0 },
        folders = prefs.getString(KEY_FOLDERS, null)?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty(),
        checkedAt = prefs.getLong(KEY_CHECKED, 0L),
    )

    private companion object {
        const val TAG = "Subsonic"
        const val KEY_ADDRESS = "address"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_PLAIN_AUTH = "plain_auth"
        const val KEY_SERVER = "server"
        const val KEY_SONGS = "song_count"
        const val KEY_FOLDERS = "folders"
        const val KEY_CHECKED = "checked_at"
        const val SEP = "\u001F"
        const val SYNC_PAGE = 500

        fun serverName(r: SubsonicResponse): String = when {
            r.type != null -> r.type.replaceFirstChar(Char::titlecase) + (r.serverVersion?.let { " $it" } ?: "")
            else -> "Subsonic API ${r.version ?: ""}".trim()
        }

        fun describe(e: Exception): String = when (e) {
            is SubsonicException -> when (e.code) {
                SubsonicException.WRONG_CREDENTIALS -> "Wrong user name or password"
                SubsonicException.NOT_AUTHORIZED -> "This user isn't allowed to use the API"
                else -> e.message ?: "Server error ${e.code}"
            }
            is UnknownHostException -> "Server not found: check the address and your internet connection"
            is UnknownServiceException -> "Only https:// addresses are supported"
            is SSLException -> "Secure connection failed: the server's certificate isn't trusted"
            is java.net.SocketTimeoutException, is java.net.ConnectException -> "The server didn't answer"
            is IOException -> e.message ?: "Couldn't reach the server"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

/** Encrypts small secrets with an AES key kept in the Android Keystore. */
private object Secrets {
    private const val ALIAS = "musicminchas-server-password"
    private const val GCM_TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    /** IV followed by the ciphertext, Base64. */
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }.onFailure { Log.w("Subsonic", "Saved password can't be decrypted", it) }.getOrNull()

    private const val IV_SIZE = 12
}
