package com.kike.musicplayer.data.subsonic

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** A Subsonic API error; [code] is the error number from http://www.subsonic.org/pages/api.jsp. */
class SubsonicException(val code: Int, message: String) : IOException(message) {
    companion object {
        const val WRONG_CREDENTIALS = 40
        /** The server can't check a password from a token (e.g. LDAP users); the password itself is sent instead. */
        const val TOKEN_AUTH_UNSUPPORTED = 41
        const val NOT_AUTHORIZED = 50
    }
}

/** Where a Subsonic server is and how to sign in. */
data class SubsonicLogin(
    /** Server address without "/rest", e.g. "https://music.example.com". */
    val baseUrl: String,
    val username: String,
    val password: String,
    /** Send the password (hex-encoded) instead of a salted token, for servers without token auth. */
    val plainAuth: Boolean = false,
)

/**
 * Subsonic API calls (http://www.subsonic.org/pages/api.jsp, with the OpenSubsonic additions that
 * Navidrome implements). Every request is signed in with a fresh salt and md5(password + salt),
 * so the password itself never goes over the wire.
 */
class SubsonicClient(private val http: OkHttpClient, private val moshi: Moshi) {
    private val random = SecureRandom()

    /** Calls [endpoint] and returns the inner response; throws [SubsonicException] for an API error. */
    suspend fun call(login: SubsonicLogin, endpoint: String, params: Map<String, String> = emptyMap()): SubsonicResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(login, endpoint, params)).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (response.code == 404) throw IOException("No Subsonic server at this address")
                if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
                val body = response.body.string()
                val parsed = runCatching { moshi.adapter(Envelope::class.java).fromJson(body)?.response }.getOrNull()
                    ?: throw IOException("This address doesn't answer like a Subsonic server")
                parsed.error?.let { throw SubsonicException(it.code, it.message ?: "Error ${it.code}") }
                if (parsed.status != "ok") throw IOException("Server answered '${parsed.status}'")
                parsed
            }
        }

    suspend fun ping(login: SubsonicLogin) = call(login, "ping")

    suspend fun musicFolders(login: SubsonicLogin): List<MusicFolderJson> =
        call(login, "getMusicFolders").musicFolders?.musicFolder.orEmpty()

    suspend fun scanStatus(login: SubsonicLogin): ScanStatusJson? = call(login, "getScanStatus").scanStatus

    /** A page of all songs: an empty search3 query lists the whole library on Navidrome. */
    suspend fun songs(login: SubsonicLogin, offset: Int, count: Int): List<SongJson> = call(
        login, "search3",
        mapOf("query" to "", "songCount" to "$count", "songOffset" to "$offset", "artistCount" to "0", "albumCount" to "0"),
    ).searchResult3?.song.orEmpty()

    /**
     * The signed URL of [endpoint], also usable for streaming and cover art. A [salt] of its own
     * gives the same URL every time (for images, so they can be cached); else each one is new.
     */
    fun url(login: SubsonicLogin, endpoint: String, params: Map<String, String> = emptyMap(), salt: String? = null): HttpUrl {
        val base = login.baseUrl.toHttpUrlOrNull() ?: throw IOException("Not a valid server address")
        return base.newBuilder().addPathSegment("rest").addPathSegment("$endpoint.view").apply {
            addQueryParameter("u", login.username)
            if (login.plainAuth) {
                addQueryParameter("p", "enc:" + login.password.toByteArray(Charsets.UTF_8).toHex())
            } else {
                val s = salt ?: ByteArray(8).also(random::nextBytes).toHex()
                addQueryParameter("t", md5(login.password + s))
                addQueryParameter("s", s)
            }
            addQueryParameter("v", API_VERSION)
            addQueryParameter("c", CLIENT)
            addQueryParameter("f", "json")
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    private class Envelope(@Json(name = "subsonic-response") val response: SubsonicResponse?)

    companion object {
        /** Subsonic 1.16.1: the last version of the original API, which OpenSubsonic servers build on. */
        const val API_VERSION = "1.16.1"
        const val CLIENT = "MusicMinchas"
        private const val USER_AGENT = "MusicMinchas (personal Android app)"

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /**
         * The server address as typed, cleaned up: https:// added when no scheme is given, and the
         * web player's own paths ("/app", "#/album/…") or a pasted "/rest" dropped. Null when it
         * isn't an address at all.
         */
        fun normalizeAddress(input: String): String? {
            var s = input.trim().substringBefore('#').trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "https://$s"
            for (suffix in listOf("/rest", "/app")) if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            val url = s.toHttpUrlOrNull() ?: return null
            return url.newBuilder().query(null).build().toString().trimEnd('/')
        }
    }
}

/** The inner "subsonic-response" object; only the part of the endpoint that was called is set. */
class SubsonicResponse(
    val status: String?,
    val version: String?,
    /** OpenSubsonic: server software, e.g. "navidrome". */
    val type: String?,
    val serverVersion: String?,
    val openSubsonic: Boolean?,
    val error: ErrorJson?,
    val musicFolders: MusicFoldersJson?,
    val scanStatus: ScanStatusJson?,
    val searchResult3: SearchResult3Json?,
)

class ErrorJson(val code: Int, val message: String?)
class MusicFoldersJson(val musicFolder: List<MusicFolderJson>?)
class MusicFolderJson(val id: String, val name: String?)
/** [count] is the number of songs on Navidrome (not part of the original API, so it may be missing). */
class ScanStatusJson(val scanning: Boolean?, val count: Long?, val folderCount: Long?, val lastScan: String?)
class SearchResult3Json(val song: List<SongJson>?)

class SongJson(
    val id: String,
    val title: String?,
    val album: String?,
    val albumId: String?,
    val artist: String?,
    val artistId: String?,
    val track: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val coverArt: String?,
    val size: Long?,
    val contentType: String?,
    val suffix: String?,
    /** Seconds. */
    val duration: Int?,
    val bitRate: Int?,
    val path: String?,
    val created: String?,
    val starred: String?,
    val musicBrainzId: String?,
    /** OpenSubsonic. */
    val displayAlbumArtist: String?,
)
