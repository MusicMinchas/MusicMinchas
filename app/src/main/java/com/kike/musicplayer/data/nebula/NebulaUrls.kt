package com.kike.musicplayer.data.nebula

import com.kike.musicplayer.util.AppLog

import android.util.Log
import com.kike.musicplayer.data.addon.AddonClient
import com.kike.musicplayer.data.addon.AddonManager
import com.kike.musicplayer.data.catalog.CatalogTrack
import com.kike.musicplayer.data.db.Track
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves stream URIs ("nebula:" or "addon:") to fresh, direct Torbox CDN links for playback
 * in ExoPlayer and Sonos.
 *
 * When multiple add-on providers (e.g. Nullbeat, Nebula) are installed, resolves from the
 * user-defined #1 provider first and automatically falls back to subsequent providers if
 * the track is unavailable, uncached, or fails.
 */
class NebulaUrls(
    private val addonManager: AddonManager? = null,
    private val addonClient: AddonClient? = null,
    private val legacyClientProvider: (() -> NebulaClient?)? = null,
    private val legacyBaseUrlProvider: (() -> String?)? = null,
) {
    /** Multi-addon constructor using [AddonManager] and [AddonClient]. */
    constructor(addonManager: AddonManager, client: AddonClient) : this(
        addonManager = addonManager,
        addonClient = client,
        legacyClientProvider = null,
        legacyBaseUrlProvider = null,
    )

    /** Legacy constructor for backward compatibility and existing tests. */
    constructor(
        clientProvider: () -> NebulaClient?,
        baseUrlProvider: () -> String?,
    ) : this(
        addonManager = null,
        addonClient = null,
        legacyClientProvider = clientProvider,
        legacyBaseUrlProvider = baseUrlProvider,
    )

    constructor(account: NebulaAccount) : this(
        clientProvider = { account.client },
        baseUrlProvider = { account.baseUrl() },
    )

    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val resolvedAddon = ConcurrentHashMap<String, String>()

    fun resolvedAddonName(uriStr: String): String? {
        val cacheKey = uriStr.removePrefix(PREFIX).removePrefix(ADDON_PREFIX)
        return resolvedAddon[cacheKey]
    }

    fun resolve(uriStr: String): String? {
        if (!uriStr.startsWith(PREFIX) && !uriStr.startsWith(ADDON_PREFIX)) return null

        val cacheKey = uriStr.removePrefix(PREFIX).removePrefix(ADDON_PREFIX)
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { (url, expiresAt) ->
            if (now < expiresAt) {
                AppLog.log(TAG, "resolve() cache HIT for $cacheKey -> $url")
                return url
            } else {
                cache.remove(cacheKey)
            }
        }

        val params = parseParams(uriStr)
        val trackId = params["id"] ?: if (uriStr.startsWith("nebula:track:")) uriStr.removePrefix("nebula:track:") else null
        val artist = params["artist"] ?: ""
        val title = params["title"] ?: ""
        val album = params["album"]
        val provider = params["provider"]

        AppLog.log(TAG, "resolve() starting for uri: $uriStr (trackId=$trackId, artist='$artist', title='$title', provider=$provider)")

        // 1. Multi-addon resolution with sequential fallback
        if (addonManager != null && addonClient != null) {
            val addons = addonManager.addonsInOrder()
            if (addons.isEmpty()) {
                AppLog.log(TAG, "resolve() no enabled add-ons configured")
                return null
            }

            for (addon in addons) {
                val baseUrl = addon.baseUrl ?: continue
                val canDirect = !trackId.isNullOrBlank() && canStreamDirectly(addon, trackId, provider)
                AppLog.log(TAG, "Checking addon '${addon.name}' (${addon.id}): canStreamDirectly=$canDirect")
                var result = if (canDirect) {
                    val idForProvider = if (addon.id == "nebula" && !trackId.startsWith("tidal:track:") && !trackId.contains(":")) {
                        "tidal:track:$trackId"
                    } else trackId
                    AppLog.log(TAG, "Calling direct stream() on '${addon.name}' with id=$idForProvider")
                    addonClient.stream(baseUrl, idForProvider)
                } else null

                // Fall back to catalog search if direct ID resolution was skipped or failed
                if ((result == null || result.isFailure) && artist.isNotBlank() && title.isNotBlank()) {
                    AppLog.log(TAG, "Direct stream skipped or failed on '${addon.name}'; falling back to findStream(artist='$artist', title='$title')")
                    result = addonClient.findStream(baseUrl, artist, album, title)
                }

                val streamUrl = result?.getOrNull()?.url
                if (!streamUrl.isNullOrBlank()) {
                    AppLog.log(TAG, "Successfully resolved stream via addon '${addon.name}': $streamUrl")
                    cache[cacheKey] = streamUrl to (now + CACHE_TTL_MS)
                    resolvedAddon[cacheKey] = addon.name
                    return streamUrl
                } else {
                    AppLog.log(TAG, "Addon '${addon.name}' failed to resolve stream (${result?.exceptionOrNull()?.message}), trying next provider...")
                }
            }
            AppLog.log(TAG, "All configured add-ons failed to resolve stream for: $artist - $title")
            return null
        }

        // 2. Fallback to single legacy provider
        val baseUrl = legacyBaseUrlProvider?.invoke() ?: return null
        val client = legacyClientProvider?.invoke() ?: return null

        val result = if (!trackId.isNullOrBlank() && trackId.startsWith("tidal:track:")) {
            client.stream(baseUrl, trackId)
        } else {
            if (artist.isBlank() || title.isBlank()) return null
            client.findStream(baseUrl, artist, album, title)
        }

        val streamUrl = result.getOrNull()?.url ?: return null
        cache[cacheKey] = streamUrl to (now + CACHE_TTL_MS)
        return streamUrl
    }

    companion object {
        private const val TAG = "NebulaUrls"
        const val PREFIX = "nebula:"
        const val ADDON_PREFIX = "addon:"
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

        fun uriFor(artist: String, album: String?, title: String, trackId: String? = null, albumId: String? = null, provider: String? = null): String {
            val encArtist = URLEncoder.encode(artist.trim(), "UTF-8")
            val encTitle = URLEncoder.encode(title.trim(), "UTF-8")
            val albumParam = if (!album.isNullOrBlank()) "&album=${URLEncoder.encode(album.trim(), "UTF-8")}" else ""
            val idParam = if (!trackId.isNullOrBlank()) "&id=${URLEncoder.encode(trackId.trim(), "UTF-8")}" else ""
            val albumIdParam = if (!albumId.isNullOrBlank()) "&albumId=${URLEncoder.encode(albumId.trim(), "UTF-8")}" else ""
            val providerParam = if (!provider.isNullOrBlank()) "&provider=${URLEncoder.encode(provider.trim(), "UTF-8")}" else ""
            return "nebula:stream?artist=$encArtist&title=$encTitle$albumParam$idParam$albumIdParam$providerParam"
        }

        fun trackFor(addonTrack: com.kike.musicplayer.data.addon.AddonTrack, providerId: String? = null): Track = Track(
            id = "nebula:${addonTrack.id}",
            source = Track.SOURCE_NEBULA,
            uri = uriFor(addonTrack.artist, addonTrack.album, addonTrack.title, addonTrack.id, provider = providerId),
            fileName = "${addonTrack.title}.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = addonTrack.title,
            artist = addonTrack.artist,
            album = addonTrack.album,
            albumArtist = addonTrack.artist,
            trackNumber = null,
            discNumber = null,
            year = null,
            genre = if (addonTrack.isHiRes == true) "Hi-Res" else "Lossless",
            durationMs = (addonTrack.duration ?: 0) * 1000L,
            artworkUri = addonTrack.artworkURL,
            mbReleaseId = null,
            scope = Track.SCOPE_STREAM,
            metadataState = Track.META_DONE,
            indexAlbum = Track.albumKeyOf(addonTrack.album, addonTrack.artist),
            indexArtist = addonTrack.artist,
            indexTitle = "",
        )

        fun trackFor(catalogTrack: CatalogTrack): Track = Track(
            id = "nebula:${catalogTrack.id}",
            source = Track.SOURCE_NEBULA,
            uri = uriFor(catalogTrack.artist, catalogTrack.album, catalogTrack.title, catalogTrack.id, catalogTrack.albumId),
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
            mbReleaseId = catalogTrack.albumId,
            scope = Track.SCOPE_STREAM,
            metadataState = Track.META_DONE,
            indexAlbum = Track.albumKeyOf(catalogTrack.album, catalogTrack.albumArtist ?: catalogTrack.artist),
            indexArtist = catalogTrack.albumArtist ?: catalogTrack.artist.orEmpty(),
            indexTitle = "",
        )
        fun canStreamDirectly(addon: com.kike.musicplayer.data.addon.AddonInstance, trackId: String, provider: String?): Boolean {
            if (!provider.isNullOrBlank() && (provider == addon.id || provider.equals(addon.name, ignoreCase = true))) {
                return true
            }
            if (trackId.startsWith("tidal:track:") && addon.id == "nebula") {
                return true
            }
            if ((trackId.startsWith("dz:") || (trackId.all { it.isDigit() } && !trackId.startsWith("a1_"))) && addon.id == "nullbeat") {
                return true
            }
            if ((trackId.startsWith("a1_") || trackId.startsWith("torbox_am_")) &&
                (addon.id.contains("apple", ignoreCase = true) || addon.id.contains("aero", ignoreCase = true) || addon.name.contains("aero", ignoreCase = true))
            ) {
                return true
            }
            if (provider.isNullOrBlank()) {
                if (addon.id == "nebula" && (trackId.startsWith("tidal:track:") || trackId.all { it.isDigit() })) return true
                if (addon.id == "nullbeat" && (trackId.startsWith("dz:") || trackId.all { it.isDigit() })) return true
                if ((trackId.startsWith("a1_") || trackId.startsWith("torbox_am_")) &&
                    (addon.id.contains("apple", ignoreCase = true) || addon.id.contains("aero", ignoreCase = true) || addon.name.contains("aero", ignoreCase = true))
                ) {
                    return true
                }
            }
            return false
        }
    }
}
