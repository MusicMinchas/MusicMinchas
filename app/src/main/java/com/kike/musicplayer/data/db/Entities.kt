package com.kike.musicplayer.data.db

import androidx.room.ColumnInfo
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
    /** Genre from the file's own tag (first one if several), as written there. */
    val genre: String? = null,
    /** Whether [genre] has been read from the file (libraries scanned before genres were read get a backfill). */
    @ColumnInfo(defaultValue = "0") val genreRead: Boolean = false,
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
    /** Place in the play order while shuffled, so a restart keeps the same shuffle; else null. */
    val shufflePosition: Int? = null,
)

@Entity(tableName = "session_state")
data class SessionState(
    @PrimaryKey val id: Int = 0,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/**
 * One play of a track, recorded when it starts playing (on the phone or on Sonos). [listenedMs] is
 * the time actually listened (seeking doesn't add to it); the play [counted] once that reached half
 * the track, like Spotify's Wrapped. Feeds Recently Played, Most Played, Replay and the recommender.
 */
@Entity(tableName = "play_history", indices = [Index("playedAt")])
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    @ColumnInfo(defaultValue = "0") val listenedMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val counted: Boolean = false,
)

/**
 * Something waiting to be sent to Last.fm (a scrobble, love or unlove), kept until Last.fm has it so
 * nothing is lost offline or across restarts. [timestamp] is in seconds: a scrobble's start time, or
 * when a love/unlove was asked for.
 */
@Entity(tableName = "lastfm_outbox")
data class LastFmOutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val artist: String,
    val track: String,
    val album: String?,
    val albumArtist: String?,
    val durationSec: Int?,
    val timestamp: Long,
) {
    companion object {
        const val SCROBBLE = "scrobble"
        const val LOVE = "love"
        const val UNLOVE = "unlove"
    }
}

/**
 * A scrobble imported from Last.fm, one per user and second ([uts]). [trackId] is the library song
 * it matched (then it was also added as a play), or null while the song isn't in the library.
 */
@Entity(tableName = "lastfm_scrobbles", primaryKeys = ["user", "uts"], indices = [Index("trackId")])
data class LastFmScrobble(
    val user: String,
    val uts: Long,
    val artist: String,
    val track: String,
    val album: String?,
    val mbid: String?,
    val trackId: String? = null,
)

/** A song marked as favourite; kept by track id, so it survives rescans. */
@Entity(tableName = "favourites")
data class Favourite(
    @PrimaryKey val trackId: String,
    val addedAt: Long,
)

/** Genre-like tags for an artist, from MusicBrainz; [name] is the lower-cased artist name. */
@Entity(tableName = "artist_info")
data class ArtistInfo(
    @PrimaryKey val name: String,
    val mbid: String?,
    /** Comma-separated, most-voted first; empty when MusicBrainz knows no tags (or no such artist). */
    val tags: String,
    val fetchedAt: Long,
) {
    val tagList: List<String> get() = if (tags.isEmpty()) emptyList() else tags.split(',')
}
