package com.kike.musicplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kike.musicplayer.data.db.Track

fun Track.toMediaItem(mediaId: String = id): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(localUri ?: uri) // the downloaded copy when there is one
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(localUri ?: uri))
                .build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(displayArtist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setTrackNumber(trackNumber)
                .setDiscNumber(discNumber)
                .setRecordingYear(year)
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

fun browsableItem(id: String, title: String, subtitle: String? = null, artworkUri: String? = null, type: Int) =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build()
        )
        .build()

/** Album ordering: disc, then track number, then title. */
val albumTrackOrder = compareBy<Track>({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() })

/** Reconstructs a transient [Track] for streamed / external tracks from [MediaItem] metadata. */
fun MediaItem.toTransientTrack(): Track? {
    val meta = mediaMetadata
    val title = meta.title?.toString() ?: return null
    val artist = meta.artist?.toString() ?: ""
    val id = PlaybackService.trackIdOf(mediaId)
    val uri = localConfiguration?.uri?.toString()
        ?: requestMetadata.mediaUri?.toString()
        ?: if (id.startsWith("nebula:")) {
            com.kike.musicplayer.data.nebula.NebulaUrls.uriFor(artist, meta.albumTitle?.toString(), title, id.removePrefix("nebula:"))
        } else ""

    val source = when {
        uri.startsWith("nebula:") || id.startsWith("nebula:") -> Track.SOURCE_NEBULA
        uri.startsWith("subsonic:") || id.startsWith("subsonic:") -> Track.SOURCE_SUBSONIC
        uri.startsWith("https://") -> Track.SOURCE_DRIVE
        else -> Track.SOURCE_SAF
    }

    val albumTitle = meta.albumTitle?.toString()
    val albumArtist = meta.albumArtist?.toString()
    val catAlbumId = if (source == Track.SOURCE_NEBULA) com.kike.musicplayer.data.nebula.NebulaUrls.parseParams(uri)["albumId"] else null

    return Track(
        id = id,
        source = source,
        scope = Track.SCOPE_STREAM,
        uri = uri,
        fileName = "$title.flac",
        mimeType = if (source == Track.SOURCE_NEBULA) "audio/flac" else "audio/mpeg",
        sizeBytes = null,
        title = title,
        artist = artist,
        album = albumTitle,
        albumArtist = albumArtist,
        trackNumber = meta.trackNumber,
        discNumber = meta.discNumber,
        year = meta.recordingYear,
        genre = null,
        durationMs = meta.durationMs ?: 0L,
        artworkUri = meta.artworkUri?.toString(),
        mbReleaseId = catAlbumId,
        metadataState = Track.META_DONE,
        indexAlbum = Track.albumKeyOf(albumTitle, albumArtist ?: artist),
        indexArtist = albumArtist ?: artist,
        indexTitle = "",
    )
}
