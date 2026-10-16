package com.kike.musicplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One playable audio file, from any source. Scanners create rows with whatever the source knows;
 * the metadata pipeline fills in tags and artwork later and records its progress in [metadataState].
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index("source"), Index("metadataState"), Index("indexAlbum"), Index("indexArtist"), Index("indexGenre"),
        Index("indexTitle"), Index("scope", "indexTitle"),
    ],
)
data class Track(
    /** "saf:<document uri>", "drive:<file id>" or "subsonic:<song id>". */
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
    /** content:// of the copy downloaded to the phone (Music/MusicMinchas), played instead of Drive. */
    val localUri: String? = null,
    /** [NOT_DOWNLOADED], [DOWNLOAD_QUEUED], [DOWNLOADED] or [DOWNLOAD_FAILED]. */
    @ColumnInfo(defaultValue = "0") val downloadState: Int = NOT_DOWNLOADED,
    /** Drive's MD5 of the file: a different one on a later sync means the file changed. */
    val checksum: String? = null,
    /**
     * Copies of [albumKey], the artist the song is listed under and its genre key, kept up to date
     * by LibraryIndexer (empty until it has seen the song), so the database can group and page
     * albums, artists and genres. Code that needs these values computes them, never reads these.
     */
    @ColumnInfo(defaultValue = "") val indexAlbum: String = "",
    @ColumnInfo(defaultValue = "") val indexArtist: String = "",
    @ColumnInfo(defaultValue = "") val indexGenre: String = "",
    /** The title for sorting the Songs tab (lower case, no accents); empty while not indexed or unplayable. */
    @ColumnInfo(defaultValue = "") val indexTitle: String = "",
    /** [scopeOf] the source, for listing each source on its own (kept right by LibraryIndexer). */
    @ColumnInfo(defaultValue = "0") val scope: Int = SCOPE_LOCAL,
) {
    val displayArtist: String get() = artist ?: albumArtist ?: "Unknown artist"
    val albumKey: String get() = albumKeyOf(album, albumArtist ?: artist)

    companion object {
        /** Device-wide MediaStore scan of earlier versions; such rows are deleted on start. */
        const val SOURCE_MEDIASTORE = "mediastore"
        const val SOURCE_SAF = "saf"
        const val SOURCE_DRIVE = "drive"
        /** A Subsonic / Navidrome server: id "subsonic:<song id>", uri "subsonic:<song id>". */
        const val SOURCE_SUBSONIC = "subsonic"

        /**
         * Where a song comes from, for switching each source on or off in the library: folders on
         * the phone, Google Drive, the Subsonic server. Set by LibraryIndexer from [source].
         */
        const val SCOPE_LOCAL = 0
        const val SCOPE_SERVER = 1
        const val SCOPE_DRIVE = 2
        val SCOPES = listOf(SCOPE_LOCAL, SCOPE_DRIVE, SCOPE_SERVER)
        /** The user's own files (the server's songs can't play yet). */
        val OWN_SCOPES = listOf(SCOPE_LOCAL, SCOPE_DRIVE)

        fun scopeOf(source: String) = when (source) {
            SOURCE_SUBSONIC -> SCOPE_SERVER
            SOURCE_DRIVE -> SCOPE_DRIVE
            else -> SCOPE_LOCAL
        }

        const val NOT_DOWNLOADED = 0
        const val DOWNLOAD_QUEUED = 1
        const val DOWNLOADED = 2
        const val DOWNLOAD_FAILED = 3

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

/** Streamed from Google Drive or the server unless downloaded; can be downloaded to the phone. */
val Track.isRemote: Boolean get() = source == Track.SOURCE_DRIVE || source == Track.SOURCE_SUBSONIC

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

/**
 * A song a sync removed while adding others: once the new files' tags are read, it is paired with
 * the same song in a new file (e.g. an MP3 re-ripped as FLAC) so plays and favourites move over
 * ([com.kike.musicplayer.data.Replacements]). Forgotten after that pass.
 */
@Entity(tableName = "replaced_tracks")
data class ReplacedTrack(
    @PrimaryKey val oldId: String,
    val source: String,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val title: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    /** The old song's download, deleted once paired. */
    val localUri: String?,
    val removedAt: Long,
)

/** A playlist the user made. */
@Entity(tableName = "playlists")
data class UserPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** A song in a user playlist, at [position] (0-based); by track id, so it survives rescans. */
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"], indices = [Index("trackId")])
data class PlaylistTrack(
    val playlistId: Long,
    val position: Int,
    val trackId: String,
)

/** A song marked as favourite; kept by track id, so it survives rescans. */
@Entity(tableName = "favourites")
data class Favourite(
    @PrimaryKey val trackId: String,
    val addedAt: Long,
)

/**
 * A song's lyrics from LRCLIB ([synced]: LRC text with timings). [found] false: none found when
 * [fetchedAt], retried later.
 */
@Entity(tableName = "lyrics")
data class StoredLyrics(
    @PrimaryKey val trackId: String,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
    val found: Boolean,
    val fetchedAt: Long,
)

/** An artist's photo for the artist page ([url] null: none found when [checkedAt]); [name] lower-cased. */
@Entity(tableName = "artist_images")
data class ArtistImage(
    @PrimaryKey val name: String,
    val url: String?,
    val checkedAt: Long,
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

/**
 * An artist the user follows for the Discover tab, by the library's artist name. The service ids
 * are looked up once: null = not yet, 0 / "" = not found there.
 */
@Entity(tableName = "followed_artists")
data class FollowedArtist(
    @PrimaryKey val name: String,
    val followedAt: Long,
    val deezerId: Long? = null,
    val ticketmasterId: String? = null,
)

/**
 * Something the Discover tab shows for a followed [artist]: a release, a concert or a news story.
 * [date]: for releases and concerts the local calendar date (and time, if known) encoded as UTC;
 * for news the publication instant. [detail]: release type, "venue · city" or the news source.
 */
@Entity(tableName = "discover_items", indices = [Index("artist")])
data class DiscoverItem(
    @PrimaryKey val id: String,
    val kind: Int,
    val artist: String,
    val title: String,
    val detail: String?,
    val date: Long,
    val imageUrl: String?,
    val url: String?,
    /** A release that isn't out yet. */
    val upcoming: Boolean = false,
) {
    companion object {
        const val RELEASE = 0
        const val CONCERT = 1
        const val NEWS = 2
    }
}

/**
 * An album for the Albums tab, kept by LibraryIndexer from the songs with this [key] in one
 * [scope] (the user's own music or the server's), so each can be listed alone or both together.
 */
@Entity(tableName = "album_index", primaryKeys = ["key", "scope"], indices = [Index("sortKey"), Index("artist")])
data class AlbumIndex(
    val key: String,
    @ColumnInfo(defaultValue = "0") val scope: Int = Track.SCOPE_LOCAL,
    val title: String,
    val artist: String,
    val year: Int?,
    val artworkUri: String?,
    val trackCount: Int,
    val durationMs: Long,
    /** Order of the Albums tab: albums without a name last, then by title (lower case, no accents). */
    val sortKey: String,
)

/** An artist for the Artists tab: the songs whose album artist (else artist) is [name]. */
@Entity(tableName = "artist_index", primaryKeys = ["name", "scope"], indices = [Index("sortKey")])
data class ArtistIndex(
    val name: String,
    @ColumnInfo(defaultValue = "0") val scope: Int = Track.SCOPE_LOCAL,
    val trackCount: Int,
    val albumCount: Int,
    val sortKey: String,
)

/** A genre for the Genres tab; [key] is lower-cased, [name] the most common spelling. */
@Entity(tableName = "genre_index", primaryKeys = ["key", "scope"], indices = [Index("sortKey")])
data class GenreIndex(
    val key: String,
    @ColumnInfo(defaultValue = "0") val scope: Int = Track.SCOPE_LOCAL,
    val name: String,
    val songCount: Int,
    val artistCount: Int,
    val albumCount: Int,
    val sortKey: String,
    /** A cover from one of the genre's albums, for the Genres tab. */
    val artworkUri: String?,
)

/**
 * Songs added, changed or removed since LibraryIndexer last ran, written by database triggers so
 * that every way of changing a song is seen. The old index values tell which album, artist and
 * genre a changed or removed song was listed under before.
 */
@Entity(tableName = "index_dirty")
data class IndexDirty(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val trackId: String?,
    val oldAlbum: String?,
    val oldArtist: String?,
    val oldGenre: String?,
)

/**
 * Full-text search over song titles, album titles and artist names (search box), keyed by the
 * row id of the song, album or artist (docid). Database triggers keep them in step, only when a
 * title or name changes. The Unicode tokenizer ignores case and accents and matches word starts,
 * so a search stays an index lookup however large the library is.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "tracks_fts")
data class TrackFts(
    val title: String,
    /** Artist and album artist, so "metallica one" finds One by Metallica. */
    val artist: String,
    val album: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "album_fts")
data class AlbumFts(val title: String)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "artist_fts")
data class ArtistFts(val name: String)

