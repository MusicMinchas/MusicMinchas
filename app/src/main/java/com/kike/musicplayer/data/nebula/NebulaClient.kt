package com.kike.musicplayer.data.nebula

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * HTTP client for the Nebula / Eclipse Music Add-on protocol.
 */
class NebulaClient(
    private val http: OkHttpClient,
    private val moshi: Moshi,
) {
    private val manifestAdapter = moshi.adapter(NebulaManifest::class.java)
    private val searchAdapter = moshi.adapter(NebulaSearchResponse::class.java)
    private val streamAdapter = moshi.adapter(NebulaStream::class.java)

    /**
     * Validates that [manifestUrl] is reachable, valid JSON, and describes a music add-on with stream capability.
     */
    fun validate(manifestUrl: String): Result<NebulaManifest> {
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
     * Searches Tidal catalog via Nebula `/search?q={query}`.
     */
    fun search(baseUrl: String, query: String): Result<NebulaSearchResponse> = runCatching {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/search?q=$encoded"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Search failed with HTTP ${resp.code}")
            val body = resp.body.string()
            searchAdapter.fromJson(body) ?: throw IOException("Invalid search response JSON")
        }
    }

    /**
     * Resolves a direct Torbox CDN stream link for a given track ID via `/stream/{trackId}`.
     */
    fun stream(baseUrl: String, trackId: String): Result<NebulaStream> = runCatching {
        val encoded = URLEncoder.encode(trackId.trim(), "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/stream/$encoded"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Stream resolution failed with HTTP ${resp.code}")
            val body = resp.body.string()
            val stream = streamAdapter.fromJson(body) ?: throw IOException("Invalid stream response JSON")
            if (stream.url.isNullOrBlank()) throw IOException("No stream URL returned for track")
            stream
        }
    }

    /**
     * Finds and resolves a stream for a song given its artist, album, and title.
     * Uses 2-stage resolution:
     * 1. Query `/search?q={artist} {title}` for high recall without over-filtering.
     * 2. Scans returned candidate tracks for matching album name; falls back to top hit.
     * 3. Resolves the selected track ID to a direct CDN stream URL.
     */
    fun findStream(baseUrl: String, artist: String, album: String?, title: String): Result<NebulaStream> {
        val query = "${artist.trim()} ${title.trim()}"
        val searchResult = search(baseUrl, query)
        val tracks = searchResult.getOrNull()?.tracks
        if (tracks.isNullOrEmpty()) {
            return Result.failure(IOException("No matching track found on Nebula for '$query'"))
        }

        val selectedTrack = if (!album.isNullOrBlank()) {
            val targetAlbum = album.lowercase().trim()
            tracks.firstOrNull { candidate ->
                val candAlbum = candidate.album?.lowercase()?.trim() ?: ""
                candAlbum.isNotEmpty() && (candAlbum in targetAlbum || targetAlbum in candAlbum)
            } ?: tracks.first()
        } else {
            tracks.first()
        }

        return stream(baseUrl, selectedTrack.id)
    }

    companion object {
        private const val USER_AGENT = "MusicMinchas/1.32.4 (Android)"
    }
}
