package com.kike.musicplayer.data.nebula

import com.squareup.moshi.Json

data class NebulaManifest(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "version") val version: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "icon") val icon: String? = null,
    @Json(name = "resources") val resources: List<String>? = null,
    @Json(name = "types") val types: List<String>? = null,
    @Json(name = "contentType") val contentType: String? = null,
)

data class NebulaSearchResponse(
    @Json(name = "tracks") val tracks: List<NebulaTrack>? = null,
    @Json(name = "albums") val albums: List<NebulaAlbum>? = null,
    @Json(name = "artists") val artists: List<NebulaArtist>? = null,
    @Json(name = "playlists") val playlists: List<NebulaPlaylist>? = null,
)

data class NebulaTrack(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "album") val album: String? = null,
    @Json(name = "duration") val duration: Int? = null,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "isrc") val isrc: String? = null,
)

data class NebulaAlbum(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "trackCount") val trackCount: Int? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "tracks") val tracks: List<NebulaTrack>? = null,
)

data class NebulaArtist(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "topTracks") val topTracks: List<NebulaTrack>? = null,
    @Json(name = "albums") val albums: List<NebulaAlbum>? = null,
)

data class NebulaPlaylist(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artworkURL") val artworkURL: String? = null,
    @Json(name = "trackCount") val trackCount: Int? = null,
)

data class NebulaStream(
    @Json(name = "url") val url: String? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "quality") val quality: String? = null,
)

/** Connection and UI state for the Nebula add-on. */
data class NebulaState(
    val manifestUrl: String? = null,
    val addonName: String? = null,
    val icon: String? = null,
    val quality: String = "auto",
    val busy: Boolean = false,
    val message: String? = null,
)
