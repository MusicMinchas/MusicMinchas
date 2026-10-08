package com.kike.musicplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One playable audio file, from any source. Scanners create rows with whatever the source knows;
 * the metadata pipeline fills in tags and artwork later and records its progress in [metadataState].
 */
@Entity(tableName = "tracks", indices = [Index("source"), Index("metadataState")])
data class Track(
    /** "saf:<document uri>" or "drive:<file id>". */
    @PrimaryKey val id: String,
    val source: String,
    /** What ExoPlayer opens: a content:// URI or the Drive download URL. */
    val uri: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    /** 0 = not read yet; [DURATION_UNPLAYABLE] = no playable audio (e.g. DRM-protected). */
    val durationMs: Long = 0,
    /** file:// of art extracted from the file, or an https Cover Art Archive URL. */
    val artworkUri: String? = null,
    val mbRecordingId: String? = null,
    val mbReleaseId: String? = null,
    val metadataState: Int = META_NEW,
    val dateAdded: Long = System.currentTimeMillis(),
) {
    val displayArtist: String get() = artist ?: albumArtist ?: "Unknown artist"
    val albumKey: String get() = albumKeyOf(album, albumArtist ?: artist)

    companion object {
        /** Device-wide MediaStore scan of earlier versions; such rows are deleted on start. */
        const val SOURCE_MEDIASTORE = "mediastore"
        const val SOURCE_SAF = "saf"
        const val SOURCE_DRIVE = "drive"

        /** Not looked at yet. */
        const val META_NEW = 0
        /** Tags read from the file; still incomplete, waiting for the online lookup. */
        const val META_LOCAL_DONE = 1
        /** Nothing more to do. */
        const val META_DONE = 2

        /** The file has no audio track we can play (e.g. FairPlay "drms"); hidden from the library. */
        const val DURATION_UNPLAYABLE = -1L

        fun albumKeyOf(album: String?, artist: String?): String =
            "${album.orEmpty().trim().lowercase()}\u001F${artist.orEmpty().trim().lowercase()}"
    }
}

@Entity(tableName = "queue_items")
data class QueueItem(
    @PrimaryKey val position: Int,
    val trackId: String,
)

@Entity(tableName = "session_state")
data class SessionState(
    @PrimaryKey val id: Int = 0,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/** One play of a track (recorded when it starts playing); feeds the automatic playlists. */
@Entity(tableName = "play_history", indices = [Index("playedAt")])
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
)
