package com.kike.musicplayer.data.lastfm

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/** A Last.fm API error; [code] is Last.fm's error number (https://www.last.fm/api/errorcodes). */
class LastFmException(val code: Int, message: String) : IOException("Last.fm error $code: $message") {
    /** Worth trying again later: the backend failed, or the service is offline, busy or rate limiting. */
    val isTemporary get() = code == OPERATION_FAILED || code == SERVICE_OFFLINE || code == TEMPORARILY_UNAVAILABLE || code == RATE_LIMITED

    companion object {
        /** "Operation failed - Most likely the backend service failed. Please try again." */
        const val OPERATION_FAILED = 8
        const val INVALID_SESSION = 9
        const val SERVICE_OFFLINE = 11
        const val TOKEN_NOT_AUTHORIZED = 14
        const val TOKEN_EXPIRED = 15
        const val TEMPORARILY_UNAVAILABLE = 16
        const val RATE_LIMITED = 29
    }
}

/**
 * Last.fm web service calls (https://www.last.fm/api): JSON responses, signed with the shared
 * secret when a method needs it, and POSTed when it writes.
 */
class LastFmClient(
    private val http: OkHttpClient,
    val moshi: Moshi,
    val apiKey: String,
    private val secret: String,
) {
    val isConfigured get() = apiKey.isNotEmpty() && secret.isNotEmpty()

    /** Calls [method] and returns the JSON body; throws [LastFmException] for an API error. */
    suspend fun call(
        method: String,
        params: Map<String, String> = emptyMap(),
        sessionKey: String? = null,
        signed: Boolean = sessionKey != null,
        post: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val all = buildMap {
            putAll(params)
            put("method", method)
            put("api_key", apiKey)
            sessionKey?.let { put("sk", it) }
        }.let { if (signed) it + ("api_sig" to sign(it, secret)) else it } + ("format" to "json")

        val request = if (post) {
            Request.Builder().url(BASE_URL).post(FormBody.Builder().apply { all.forEach { (k, v) -> add(k, v) } }.build())
        } else {
            Request.Builder().url(BASE_URL.toHttpUrl().newBuilder().apply { all.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
        }.header("User-Agent", USER_AGENT).build()

        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            // Errors come as {"error": n, "message": "..."}, whatever the HTTP status.
            val error = runCatching { moshi.adapter(ErrorJson::class.java).fromJson(body) }.getOrNull()
            if (error?.error != null) throw LastFmException(error.error, error.message.orEmpty())
            if (!response.isSuccessful) throw IOException("Last.fm HTTP ${response.code}")
            body
        }
    }

    inline fun <reified T> parse(json: String): T =
        moshi.adapter(T::class.java).fromJson(json) ?: throw IOException("Empty Last.fm response")

    private class ErrorJson(val error: Int?, val message: String?)

    companion object {
        const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
        const val AUTH_URL = "https://www.last.fm/api/auth/"
        private const val USER_AGENT = "MusicMinchas (personal Android app)"

        /**
         * The api_sig of a call: every parameter except format and callback, sorted by name, as
         * name+value with no separators, then the shared secret, MD5 in lower-case hex (UTF-8).
         */
        fun sign(params: Map<String, String>, secret: String): String {
            val text = params.filterKeys { it != "format" && it != "callback" }.toSortedMap()
                .entries.joinToString("") { (k, v) -> k + v } + secret
            return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
