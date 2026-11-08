package com.kike.musicplayer.data.addon

import com.kike.musicplayer.util.AppLog

import android.util.Log
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * HTTP client for Stremio / Eclipse Music Add-on protocols (Nullbeat, Nebula, etc.).
 */
class AddonClient(
    private val http: OkHttpClient,
    private val moshi: Moshi,
) {
    private val manifestAdapter = moshi.adapter(AddonManifest::class.java)
    private val searchAdapter = moshi.adapter(AddonSearchResponse::class.java)
    private val streamAdapter = moshi.adapter(AddonStream::class.java)

    /**
     * Validates that [manifestUrl] is reachable, valid JSON, and describes a music add-on with stream capability.
     */
    fun validate(manifestUrl: String): Result<AddonManifest> {
        val trimmed = manifestUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Manifest URL must start with http:// or https://"))
        }
        val targetUrl = if (trimmed.endsWith("/manifest.json", ignoreCase = true)) trimmed
        else trimmed.trimEnd('/') + "/manifest.json"

        return runCatching {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                val body = resp.body.string()
                val manifest = manifestAdapter.fromJson(body) ?: throw IOException("Invalid manifest JSON")
                val hasStream = manifest.resources?.any { it.equals("stream", ignoreCase = true) } == true
                if (!hasStream) {
                    throw IOException("Add-on manifest does not support audio streaming (missing 'stream' resource)")
                }
                manifest
            }
        }
    }

    /**
     * Searches catalog via `/search?q={query}`.
     */
    fun search(baseUrl: String, query: String): Result<AddonSearchResponse> = runCatching {
        val clean = sanitizeForSearch(query).trim()
        if (clean.isEmpty()) return Result.success(AddonSearchResponse())
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/search?q=$encoded"
        AppLog.log(TAG, "search() GET $url")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            AppLog.log(TAG, "search() HTTP ${resp.code} for $url")
            if (!resp.isSuccessful) throw IOException("Search failed with HTTP ${resp.code}")
            val body = resp.body.string()
            searchAdapter.fromJson(body) ?: throw IOException("Invalid search response JSON")
        }
    }.onFailure {
        AppLog.log(TAG, "search() failed for query='$query' at $baseUrl", it)
    }

    /**
     * Resolves a direct CDN stream link for a given track ID via `/stream/{trackId}`.
     */
    fun stream(baseUrl: String, trackId: String): Result<AddonStream> = runCatching {
        val encoded = URLEncoder.encode(trackId.trim(), "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/stream/$encoded"
        AppLog.log(TAG, "stream() GET $url")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            AppLog.log(TAG, "stream() HTTP ${resp.code} for $url")
            if (!resp.isSuccessful) throw IOException("Stream resolution failed with HTTP ${resp.code}")
            val body = resp.body.string()
            AppLog.log(TAG, "stream() body: $body")
            val stream = streamAdapter.fromJson(body) ?: throw IOException("Invalid stream response JSON")
            if (stream.url.isNullOrBlank()) throw IOException("No stream URL returned for track")
            stream
        }
    }.onFailure {
        AppLog.log(TAG, "stream() failed for trackId=$trackId, baseUrl=$baseUrl", it)
    }

    /**
     * Performs a fast 1-byte Range probe to verify that a resolved CDN stream URL actually exists
     * (returns HTTP 200 or 206 rather than 404/403/5xx) and pre-warms the OkHttp TLS connection pool.
     */
    fun isStreamReachable(streamUrl: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        http.newCall(req).execute().use { resp ->
            val ok = resp.code == 200 || resp.code == 206
            if (!ok) {
                Log.w(TAG, "isStreamReachable() HTTP ${resp.code} for $streamUrl")
            }
            ok
        }
    }.onFailure {
        Log.w(TAG, "isStreamReachable() failed for $streamUrl: $it")
    }.getOrDefault(false)

    /**
     * Finds and resolves a stream for a song given its artist, album, and title.
     * Uses 2-stage resolution:
     * 1. Query `/search?q={artist} {title}` for high recall without over-filtering.
     * 2. Scans returned candidate tracks for matching album name; falls back to top hits.
     * 3. Resolves the selected track ID via `/stream/{id}` (trying up to 3 candidates if a candidate fails or 404s).
     */
    fun findStream(
        baseUrl: String,
        artist: String,
        album: String?,
        title: String,
        verifyReachability: Boolean = false,
    ): Result<AddonStream> {
        val cleanArtist = sanitizeForSearch(artist)
        val cleanTitle = sanitizeForSearch(title)
        val query = "$cleanArtist $cleanTitle".trim()
        AppLog.log(TAG, "findStream() searching '$query' at $baseUrl")
        val searchResult = search(baseUrl, query)
        val tracks = searchResult.getOrNull()?.tracks
        if (tracks.isNullOrEmpty()) {
            AppLog.log(TAG, "findStream() no matching tracks found for '$query'")
            return Result.failure(IOException("No matching track found for '$query'"))
        }

        val orderedCandidates = if (!album.isNullOrBlank()) {
            val targetAlbum = sanitizeForSearch(album).lowercase()
            val (matching, rest) = tracks.partition { candidate ->
                val candAlbum = sanitizeForSearch(candidate.album ?: "").lowercase()
                candAlbum.isNotEmpty() && (candAlbum in targetAlbum || targetAlbum in candAlbum)
            }
            matching + rest
        } else {
            tracks
        }

        var lastFailure: Result<AddonStream>? = null
        for (candidate in orderedCandidates.take(3)) {
            AppLog.log(TAG, "findStream() trying '${candidate.title}' (id=${candidate.id}) for '$query'")
            val res = stream(baseUrl, candidate.id)
            val url = res.getOrNull()?.url
            if (!url.isNullOrBlank()) {
                if (!verifyReachability || isStreamReachable(url)) {
                    return res
                }
                Log.w(TAG, "findStream() candidate '${candidate.title}' (${candidate.id}) returned unreachable URL, trying next")
                lastFailure = Result.failure(IOException("Stream URL returned 404/unreachable for ${candidate.id}"))
            } else {
                lastFailure = res
            }
        }
        return lastFailure ?: Result.failure(IOException("No playable stream found for '$query'"))
    }

    companion object {
        private const val TAG = "AddonClient"
        private const val USER_AGENT = "MusicMinchas/1.41.0 (Android)"
        private val PUNCTUATION_REGEX = Regex("[()\\[\\]{}\"]")
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Replaces reserved FTS query operators (parentheses, brackets, quotes) with spaces
         * so search backends (like Nullbeat/Lucene) do not fail on parenthetical expressions.
         */
        fun sanitizeForSearch(text: String): String =
            text.replace(PUNCTUATION_REGEX, " ")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
    }
}
