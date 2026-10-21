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
