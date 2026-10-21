package com.kike.musicplayer.data.nebula

import com.kike.musicplayer.data.catalog.CatalogTrack
import com.kike.musicplayer.data.db.Track
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves "nebula:" stream URIs to fresh, direct Torbox CDN links for playback in ExoPlayer and Sonos.
 * Also converts unowned catalog tracks into transient playable [Track] instances.
 */
class NebulaUrls(
    private val clientProvider: () -> NebulaClient?,
    private val baseUrlProvider: () -> String?,
) {
    constructor(account: NebulaAccount) : this(
        clientProvider = { account.client },
        baseUrlProvider = { account.baseUrl() },
    )

    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    fun resolve(uriStr: String): String? {
        if (!uriStr.startsWith(PREFIX)) return null
        val baseUrl = baseUrlProvider() ?: return null
        val client = clientProvider() ?: return null

        val cacheKey = uriStr.removePrefix(PREFIX)
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { (url, expiresAt) ->
            if (now < expiresAt) return url else cache.remove(cacheKey)
        }

        val params = parseParams(uriStr)
        val trackId = params["id"] ?: if (uriStr.startsWith("nebula:track:")) uriStr.removePrefix("nebula:track:") else null

        val result = if (!trackId.isNullOrBlank() && trackId.startsWith("tidal:track:")) {
            client.stream(baseUrl, trackId)
        } else {
            val artist = params["artist"] ?: ""
            val title = params["title"] ?: ""
            val album = params["album"]
            if (artist.isBlank() || title.isBlank()) return null
            client.findStream(baseUrl, artist, album, title)
        }

        val streamUrl = result.getOrNull()?.url ?: return null
        cache[cacheKey] = streamUrl to (now + CACHE_TTL_MS)
        return streamUrl
    }

    companion object {
        const val PREFIX = "nebula:"
        const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour

        fun parseParams(uriStr: String): Map<String, String> {
            val qIdx = uriStr.indexOf('?')
            if (qIdx == -1 || qIdx == uriStr.length - 1) return emptyMap()
            val query = uriStr.substring(qIdx + 1)
            return query.split('&').mapNotNull { param ->
                val eqIdx = param.indexOf('=')
                if (eqIdx > 0) {
                    val key = runCatching { URLDecoder.decode(param.substring(0, eqIdx), "UTF-8") }.getOrNull() ?: ""
                    val value = runCatching { URLDecoder.decode(param.substring(eqIdx + 1), "UTF-8") }.getOrNull() ?: ""
                    if (key.isNotEmpty()) key to value else null
                } else null
            }.toMap()
        }

        fun uriFor(artist: String, album: String?, title: String, trackId: String? = null): String {
            val encArtist = URLEncoder.encode(artist.trim(), "UTF-8")
            val encTitle = URLEncoder.encode(title.trim(), "UTF-8")
            val albumParam = if (!album.isNullOrBlank()) "&album=${URLEncoder.encode(album.trim(), "UTF-8")}" else ""
            val idParam = if (!trackId.isNullOrBlank()) "&id=${URLEncoder.encode(trackId.trim(), "UTF-8")}" else ""
            return "nebula:stream?artist=$encArtist&title=$encTitle$albumParam$idParam"
        }

        fun trackFor(catalogTrack: CatalogTrack): Track = Track(
            id = "nebula:${catalogTrack.id}",
            source = Track.SOURCE_NEBULA,
            uri = uriFor(catalogTrack.artist, catalogTrack.album, catalogTrack.title, catalogTrack.id),
            fileName = "${catalogTrack.title}.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = catalogTrack.title,
            artist = catalogTrack.artist,
            album = catalogTrack.album,
            albumArtist = catalogTrack.albumArtist,
            trackNumber = catalogTrack.trackNumber,
            discNumber = catalogTrack.discNumber,
            year = catalogTrack.year,
            genre = catalogTrack.genre,
            durationMs = catalogTrack.durationMs,
            artworkUri = catalogTrack.coverUrl,
        )
    }
}
