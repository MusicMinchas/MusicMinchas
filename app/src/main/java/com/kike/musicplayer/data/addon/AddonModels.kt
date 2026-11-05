package com.kike.musicplayer.data.addon

import com.squareup.moshi.Json

data class AddonManifest(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "version") val version: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "icon") val icon: String? = null,
    @Json(name = "resources") val resources: List<String>? = null,
    @Json(name = "types") val types: List<String>? = null,
    @Json(name = "contentType") val contentType: String? = null,
)

data class AddonSearchResponse(
    @Json(name = "tracks") val tracks: List<AddonTrack>? = null,
    @Json(name = "albums") val albums: List<AddonAlbum>? = null,
    @Json(name = "artists") val artists: List<AddonArtist>? = null,
    @Json(name = "playlists") val playlists: List<AddonPlaylist>? = null,
)

data class AddonSearchResults(
    val tracks: List<AddonTrack> = emptyList(),
    val albums: List<AddonAlbum> = emptyList(),
    val artists: List<AddonArtist> = emptyList(),
    val addonName: String = "Add-on",
    val addonId: String? = null,
    val loading: Boolean = false,
)

data class AddonTrack(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "album") val album: String? = null,
    @Json(name = "duration") val duration: Int? = null,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "isrc") val isrc: String? = null,
    @Json(name = "isHiRes") val isHiRes: Boolean? = null,
)

data class AddonAlbum(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "trackCount") val trackCount: Int? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "tracks") val tracks: List<AddonTrack>? = null,
)

data class AddonArtist(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "topTracks") val topTracks: List<AddonTrack>? = null,
    @Json(name = "albums") val albums: List<AddonAlbum>? = null,
)

data class AddonPlaylist(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "trackCount") val trackCount: Int? = null,
)

data class AddonStream(
    @Json(name = "url") val url: String? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "quality") val quality: String? = null,
)

/**
 * A configured, installed music add-on provider (e.g. Nullbeat, Nebula).
 */
data class AddonInstance(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "manifestUrl") val manifestUrl: String,
    @Json(name = "icon") val icon: String? = null,
    @Json(name = "quality") val quality: String = "auto",
    @Json(name = "enabled") val enabled: Boolean = true,
) {
    /** Returns the base endpoint URL (e.g. `https://host/token`). */
    val baseUrl: String?
        get() = manifestUrl.split("/manifest.json")[0].trimEnd('/')
}
