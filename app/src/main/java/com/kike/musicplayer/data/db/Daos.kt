package com.kike.musicplayer.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class DownloadTotals(val songs: Int, val bytes: Long)

@Dao
interface TrackDao {
    // @Transaction: a large library spans several CursorWindows; without a read transaction a
    // concurrent write between window refills makes rows vanish ("Couldn't read row N").
    @Transaction
    @Query("SELECT * FROM tracks WHERE durationMs >= 0 AND scope IN (:scopes) ORDER BY title COLLATE NOCASE")
    suspend fun getAll(scopes: List<Int>): List<Track>

    @Transaction
    @Query("SELECT * FROM tracks WHERE source = :source")
    suspend fun getBySource(source: String): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): Track?

    @Query("UPDATE tracks SET downloadState = :state WHERE id IN (:ids)")
    suspend fun setDownloadState(ids: List<String>, state: Int)

    @Query("UPDATE tracks SET localUri = :uri, downloadState = :state WHERE id = :id")
    suspend fun setLocal(id: String, uri: String?, state: Int)

    /** The download queue, album by album in track order. */
    @Query(
        "SELECT * FROM tracks WHERE downloadState = ${Track.DOWNLOAD_QUEUED} " +
            "ORDER BY albumArtist, album, discNumber, trackNumber, title"
    )
    suspend fun downloadQueued(): List<Track>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE source = :source")
    suspend fun countSource(source: String): Int

    /** Songs of a source downloaded to the phone. */
    @Query("SELECT COUNT(*) FROM tracks WHERE source = :source AND downloadState = ${Track.DOWNLOADED}")
    fun observeDownloadedCount(source: String): Flow<Int>

    /** Covers extracted from the files (file:// URIs), to tidy the folder they're kept in. */
    @Query("SELECT DISTINCT artworkUri FROM tracks WHERE artworkUri LIKE 'file:%'")
    suspend fun localArtworkUris(): List<String>

    /** Id and checksum of a source's songs, to see what a sync changed without loading whole rows. */
    @Query("SELECT id, checksum FROM tracks WHERE source = :source")
    suspend fun checksums(source: String): List<IdChecksum>

    @Query("SELECT COUNT(*) FROM tracks WHERE downloadState = ${Track.DOWNLOAD_QUEUED}")
    suspend fun countDownloadQueued(): Int

    @Query("SELECT * FROM tracks WHERE downloadState = ${Track.DOWNLOADED} AND localUri IS NOT NULL")
    suspend fun downloaded(): List<Track>

    /** Songs on the phone and the bytes they take (each download is the Drive file, byte for byte). */
    @Query(
        "SELECT COUNT(*) AS songs, COALESCE(SUM(sizeBytes), 0) AS bytes FROM tracks " +
            "WHERE downloadState = ${Track.DOWNLOADED} AND localUri IS NOT NULL"
    )
    fun observeDownloadTotals(): Flow<DownloadTotals>

    @Query("UPDATE tracks SET downloadState = ${Track.NOT_DOWNLOADED} WHERE downloadState IN (${Track.DOWNLOAD_QUEUED}, ${Track.DOWNLOAD_FAILED})")
    suspend fun cancelQueuedDownloads()

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIdsChunk(ids: List<String>): List<Track>

    /** Every artist / album artist spelling in the library (for matching scrobbles by artist). */
    @Query("SELECT DISTINCT artist, albumArtist FROM tracks")
    suspend fun artistPairs(): List<ArtistPair>

    @Query("SELECT * FROM tracks WHERE artist IN (:names) OR albumArtist IN (:names)")
    suspend fun byArtistNamesChunk(names: List<String>): List<Track>

    @Query("SELECT * FROM tracks WHERE mbRecordingId IN (:ids)")
    suspend fun byRecordingIdsChunk(ids: List<String>): List<Track>

    @Query("SELECT COUNT(*) FROM tracks WHERE durationMs >= 0 AND scope IN (:scopes)")
    suspend fun countPlayable(scopes: List<Int>): Int

    /** Every artist and album artist in the library (for the genre lookup). */
    @Query(
        "SELECT DISTINCT artist FROM tracks WHERE artist IS NOT NULL AND durationMs >= 0 AND scope <> ${Track.SCOPE_SERVER} " +
            "UNION SELECT DISTINCT albumArtist FROM tracks WHERE albumArtist IS NOT NULL AND durationMs >= 0 AND scope <> ${Track.SCOPE_SERVER}"
    )
    suspend fun artistNames(): List<String>

    /** Tracks whose tags were read before the genre tag was: they need a genre-only read. */
    @Query("SELECT id FROM tracks WHERE genreRead = 0 AND durationMs >= 0 AND metadataState != ${Track.META_NEW}")
    suspend fun idsMissingGenre(): List<String>

    @Query("UPDATE tracks SET genre = :genre, genreRead = 1 WHERE id = :id")
    suspend fun setGenre(id: String, genre: String?)

    /** One transaction, so the library is refreshed once per batch rather than once per track. */
    @Transaction
    suspend fun setGenres(genres: List<Pair<String, String?>>) {
        genres.forEach { (id, genre) -> setGenre(id, genre) }
    }

    @Query("SELECT id FROM tracks WHERE metadataState = :state")
    suspend fun idsWithState(state: Int): List<String>

    @Query("SELECT COUNT(*) FROM tracks WHERE metadataState < ${Track.META_DONE}")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE durationMs = ${Track.DURATION_UNPLAYABLE}")
    fun observeUnplayableCount(): Flow<Int>

    @Query(
        "UPDATE tracks SET metadataState = ${Track.META_LOCAL_DONE} WHERE metadataState = ${Track.META_DONE} " +
            "AND (artist IS NULL OR album IS NULL OR artworkUri IS NULL) AND scope <> ${Track.SCOPE_SERVER}"
    )
    suspend fun requeueIncomplete(): Int

    /**
     * Tracks without a duration were never read successfully (or are MP3s read before durations were
     * estimated); queue them for another tag read.
     */
    @Query("UPDATE tracks SET metadataState = ${Track.META_NEW} WHERE durationMs = 0 AND metadataState != ${Track.META_NEW} AND scope <> ${Track.SCOPE_SERVER}")
    suspend fun requeueUnread(): Int

    /**
     * Songs MusicBrainz identified (it named them when the file had no Artist tag, title included):
     * read their tags again, so the files' own titles come back.
     */
    @Query("UPDATE tracks SET metadataState = ${Track.META_NEW} WHERE mbRecordingId IS NOT NULL AND scope <> ${Track.SCOPE_SERVER}")
    suspend fun requeueMusicBrainzMatched(): Int

    @Upsert
    suspend fun upsertAll(tracks: List<Track>)

    @Upsert
    suspend fun upsert(track: Track)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteChunk(ids: List<String>)

    @Query("DELETE FROM tracks WHERE source = :source")
    suspend fun deleteSource(source: String)
}

/** SQLite caps bound variables, so large IN (...) lists go in chunks. */
suspend fun TrackDao.getByIds(ids: Collection<String>): List<Track> =
    ids.chunked(500).flatMap { getByIdsChunk(it) }

suspend fun TrackDao.byArtistNames(names: Collection<String>): List<Track> =
    names.chunked(250).flatMap { byArtistNamesChunk(it) }.distinctBy { it.id }

suspend fun TrackDao.byRecordingIds(ids: Collection<String>): List<Track> =
    ids.chunked(500).flatMap { byRecordingIdsChunk(it) }

data class ArtistPair(val artist: String?, val albumArtist: String?)

data class SourceCount(val source: String, val count: Int)

/** A song's album and source, for recently added albums. */
data class AddedAlbum(val key: String, val scope: Int)

data class NameCount(val name: String, val count: Int)

data class IdChecksum(val id: String, val checksum: String?)

suspend fun TrackDao.delete(ids: Collection<String>) =
    ids.chunked(500).forEach { deleteChunk(it) }

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun items(): List<QueueItem>

    @Query("SELECT * FROM session_state WHERE id = 0")
    suspend fun state(): SessionState?

    @Query("DELETE FROM queue_items")
    suspend fun clearItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<QueueItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: SessionState)

    @Transaction
    suspend fun save(trackIds: List<String>, playOrder: List<Int>?, state: SessionState) {
        clearItems()
        val shufflePosition = playOrder?.withIndex()?.associate { (place, index) -> index to place }
        insertItems(trackIds.mapIndexed { i, id -> QueueItem(i, id, shufflePosition?.get(i)) })
        insertState(state)
    }
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistory): Long

    @Insert
    suspend fun insertAll(entries: List<PlayHistory>)

    /** Start times of every play, to spot imported duplicates. */
    @Query("SELECT playedAt FROM play_history")
    suspend fun allPlayedAt(): List<Long>

    @Query("UPDATE play_history SET listenedMs = :listenedMs, counted = :counted WHERE id = :id")
    suspend fun updateListening(id: Long, listenedMs: Long, counted: Boolean)

    /** The whole history, newest first (kept forever: Replay looks back years). */
    @Transaction
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    fun observeAll(): Flow<List<PlayHistory>>
}

@Dao
interface ServerOutboxDao {
    @Insert
    suspend fun insert(item: ServerOutboxItem)

    /** Oldest first. */
    @Query("SELECT * FROM server_outbox ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<ServerOutboxItem>

    @Query("DELETE FROM server_outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM server_outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM server_outbox")
    suspend fun clear()
}

@Dao
interface LastFmOutboxDao {
    @Insert
    suspend fun insert(item: LastFmOutboxItem)

    /** Oldest first. */
    @Query("SELECT * FROM lastfm_outbox WHERE action = :action ORDER BY id LIMIT :limit")
    suspend fun pending(action: String, limit: Int): List<LastFmOutboxItem>

    /** Loves and unloves, in the order they were asked for. */
    @Query("SELECT * FROM lastfm_outbox WHERE action IN ('love', 'unlove') ORDER BY id LIMIT :limit")
    suspend fun pendingLoves(limit: Int): List<LastFmOutboxItem>

    @Query("DELETE FROM lastfm_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM lastfm_outbox WHERE action = :action")
    fun observeCount(action: String): Flow<Int>
}

data class ScrobbleCounts(val total: Int, val unmatched: Int)

@Dao
interface LastFmScrobbleDao {
    /** Row ids, -1 for scrobbles already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(scrobbles: List<LastFmScrobble>): List<Long>

    /** Time of the newest imported scrobble, so the next import only fetches what came after. */
    @Query("SELECT MAX(uts) FROM lastfm_scrobbles WHERE user = :user")
    suspend fun latest(user: String): Long?

    @Query("SELECT * FROM lastfm_scrobbles WHERE trackId IS NULL")
    suspend fun unmatched(): List<LastFmScrobble>

    @Update
    suspend fun setTrackIds(scrobbles: List<LastFmScrobble>)

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(trackId IS NULL), 0) AS unmatched FROM lastfm_scrobbles WHERE user = :user")
    fun observeCounts(user: String): Flow<ScrobbleCounts>
}

@Dao
interface ReplacementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<ReplacedTrack>)

    @Query("SELECT * FROM replaced_tracks")
    suspend fun all(): List<ReplacedTrack>

    @Query("DELETE FROM replaced_tracks WHERE oldId IN (:ids)")
    suspend fun delete(ids: List<String>)

    /** Songs added since [since] (by a sync), with their tags read. */
    @Query("SELECT * FROM tracks WHERE dateAdded >= :since AND metadataState != ${Track.META_NEW} AND durationMs >= 0 AND scope <> ${Track.SCOPE_SERVER}")
    suspend fun addedSince(since: Long): List<Track>

    @Query("SELECT COUNT(*) FROM tracks WHERE dateAdded >= :since AND metadataState = ${Track.META_NEW}")
    suspend fun unreadSince(since: Long): Int

    @Query("UPDATE play_history SET trackId = :newId WHERE trackId = :oldId")
    suspend fun movePlays(oldId: String, newId: String)

    @Query("INSERT OR IGNORE INTO favourites (trackId, addedAt) SELECT :newId, addedAt FROM favourites WHERE trackId = :oldId")
    suspend fun copyFavourite(oldId: String, newId: String)

    @Query("DELETE FROM favourites WHERE trackId = :oldId")
    suspend fun deleteFavourite(oldId: String)

    @Query("UPDATE lastfm_scrobbles SET trackId = :newId WHERE trackId = :oldId")
    suspend fun moveScrobbles(oldId: String, newId: String)

    @Query("UPDATE queue_items SET trackId = :newId WHERE trackId = :oldId")
    suspend fun moveQueue(oldId: String, newId: String)

    @Query("UPDATE playlist_tracks SET trackId = :newId WHERE trackId = :oldId")
    suspend fun movePlaylistEntries(oldId: String, newId: String)

    /** Everything the app knows about a song by its id moves to the new file's id. */
    @Transaction
    suspend fun moveSong(oldId: String, newId: String) {
        movePlays(oldId, newId)
        copyFavourite(oldId, newId)
        deleteFavourite(oldId)
        moveScrobbles(oldId, newId)
        moveQueue(oldId, newId)
        movePlaylistEntries(oldId, newId)
    }
}

@Dao
interface PlaylistDao {
    /** Newest first. */
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<UserPlaylist>>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position")
    fun observeEntries(): Flow<List<PlaylistTrack>>

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun all(): List<UserPlaylist>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun get(playlistId: Long): UserPlaylist?

    /** Records the playlist's server counterpart and its state at this sync. */
    @Query("UPDATE playlists SET serverId = :serverId, serverChanged = :serverChanged, syncedHash = :syncedHash WHERE id = :playlistId")
    suspend fun setSynced(playlistId: Long, serverId: String?, serverChanged: String?, syncedHash: String?)

    @Insert
    suspend fun insertPlaylist(playlist: UserPlaylist): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistTrack>)

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun trackIds(playlistId: Long): List<String>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Transaction
    suspend fun delete(playlistId: Long) {
        deleteEntries(playlistId)
        deletePlaylist(playlistId)
    }

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String)

    /** Rewrites the playlist's songs as [trackIds], in that order. */
    @Transaction
    suspend fun setTracks(playlistId: Long, trackIds: List<String>) {
        deleteEntries(playlistId)
        insertEntries(trackIds.mapIndexed { i, t -> PlaylistTrack(playlistId, i, t) })
    }

    @Transaction
    suspend fun remove(playlistId: Long, trackId: String) = setTracks(playlistId, trackIds(playlistId) - trackId)

    /**
     * Puts the playlist in the order of [shown] (its songs as listed); songs not listed (no longer
     * in the library) keep their place at the end.
     */
    @Transaction
    suspend fun reorder(playlistId: Long, shown: List<String>) {
        val all = trackIds(playlistId)
        setTracks(playlistId, shown.filter { it in all } + all.filter { it !in shown })
    }

    /** Adds [trackIds] at the end, leaving out songs already in it; returns how many were added. */
    @Transaction
    suspend fun append(playlistId: Long, trackIds: List<String>): Int {
        val existing = trackIds(playlistId)
        val new = trackIds.distinct().filter { it !in existing }
        insertEntries(new.mapIndexed { i, t -> PlaylistTrack(playlistId, existing.size + i, t) })
        return new.size
    }

    /** Makes a playlist of [trackIds], in that order; returns its id. */
    @Transaction
    suspend fun create(name: String, trackIds: List<String>): Long {
        val id = insertPlaylist(UserPlaylist(name = name, createdAt = System.currentTimeMillis()))
        insertEntries(trackIds.mapIndexed { i, t -> PlaylistTrack(id, i, t) })
        return id
    }
}

@Dao
interface FavouriteDao {
    /** Newest first. */
    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Favourite>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favourite: Favourite)

    /** Returns the row ids, -1 for songs that were favourites already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(favourites: List<Favourite>): List<Long>

    @Query("DELETE FROM favourites WHERE trackId = :trackId")
    suspend fun delete(trackId: String)

    @Query("SELECT trackId FROM favourites")
    suspend fun allTrackIds(): List<String>

    /** Favourites that are songs on the Subsonic server. */
    @Query("SELECT trackId FROM favourites WHERE trackId LIKE 'subsonic:%'")
    suspend fun serverTrackIds(): List<String>
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: String): StoredLyrics?

    @Upsert
    suspend fun upsert(lyrics: StoredLyrics)
}

@Dao
interface ArtistImageDao {
    @Query("SELECT * FROM artist_images WHERE name = :name")
    suspend fun get(name: String): ArtistImage?

    @Query("SELECT * FROM artist_images")
    fun observeAll(): Flow<List<ArtistImage>>

    @Upsert
    suspend fun upsert(image: ArtistImage)
}

@Dao
interface ArtistInfoDao {
    @Query("SELECT * FROM artist_info")
    fun observeAll(): Flow<List<ArtistInfo>>

    @Query("SELECT name FROM artist_info")
    suspend fun names(): List<String>

    @Query("SELECT * FROM artist_info")
    suspend fun all(): List<ArtistInfo>

    @Query("SELECT * FROM artist_info WHERE name = :name")
    suspend fun get(name: String): ArtistInfo?

    @Upsert
    suspend fun upsert(info: ArtistInfo)
}

@Dao
interface DiscoverDao {
    @Query("SELECT * FROM followed_artists ORDER BY name COLLATE NOCASE")
    fun observeFollowed(): Flow<List<FollowedArtist>>

    @Query("SELECT * FROM followed_artists")
    suspend fun followed(): List<FollowedArtist>

    @Query("SELECT * FROM followed_artists WHERE name = :name")
    suspend fun followed(name: String): FollowedArtist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun follow(artist: FollowedArtist)

    @Update
    suspend fun update(artist: FollowedArtist)

    @Query("DELETE FROM followed_artists WHERE name = :name")
    suspend fun deleteFollowed(name: String)

    @Query("DELETE FROM discover_items WHERE artist = :artist")
    suspend fun clearArtist(artist: String)

    @Transaction
    suspend fun unfollow(name: String) {
        deleteFollowed(name)
        clearArtist(name)
    }

    @Query("SELECT * FROM discover_items ORDER BY date")
    fun observeItems(): Flow<List<DiscoverItem>>

    @Query("DELETE FROM discover_items WHERE artist = :artist AND kind = :kind")
    suspend fun clear(artist: String, kind: Int)

    @Query("DELETE FROM discover_items WHERE kind = :kind")
    suspend fun clearKind(kind: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DiscoverItem>)

    /** One artist's items of one kind, as just fetched. */
    @Transaction
    suspend fun replace(artist: String, kind: Int, items: List<DiscoverItem>) {
        clear(artist, kind)
        insertAll(items)
    }
}

data class ArtistCounts(val name: String, val scope: Int, val trackCount: Int, val albumCount: Int)

data class GenreCounts(val key: String, val scope: Int, val songCount: Int, val artistCount: Int, val albumCount: Int)

/** The album, artist and genre summaries, and the queue of songs to (re)index (see LibraryIndexer). */
@Dao
interface LibraryIndexDao {
    @Query("SELECT COUNT(*) FROM index_dirty")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT * FROM index_dirty WHERE seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun dirty(afterSeq: Long, limit: Int): List<IndexDirty>

    @Query("DELETE FROM index_dirty WHERE seq <= :seq")
    suspend fun clearDirty(seq: Long)

    /** Songs without a genre of their own take their artist's: index them again when artist tags change. */
    @Query("INSERT INTO index_dirty(trackId) SELECT id FROM tracks WHERE genre IS NULL")
    suspend fun markUntaggedDirty()

    /** Everything again (the indexing rules changed); the summaries are rewritten as songs are seen. */
    @Query("INSERT INTO index_dirty(trackId) SELECT id FROM tracks")
    suspend fun markAllDirty()

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracks(ids: List<String>): List<Track>

    @Query("UPDATE tracks SET indexAlbum = :album, indexArtist = :artist, indexGenre = :genre, indexTitle = :title, scope = :scope WHERE id = :id")
    suspend fun setIndex(id: String, album: String, artist: String, genre: String, title: String, scope: Int)

    @Query("SELECT * FROM tracks WHERE indexAlbum IN (:keys)")
    suspend fun tracksOfAlbums(keys: List<String>): List<Track>

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumIndex>)

    @Query("DELETE FROM album_index WHERE scope = :scope AND `key` IN (:keys)")
    suspend fun deleteAlbums(keys: List<String>, scope: Int)

    @Query(
        "SELECT indexArtist AS name, scope, COUNT(*) AS trackCount, COUNT(DISTINCT indexAlbum) AS albumCount " +
            "FROM tracks WHERE indexArtist IN (:names) GROUP BY indexArtist, scope"
    )
    suspend fun artistCounts(names: List<String>): List<ArtistCounts>

    @Upsert
    suspend fun upsertArtists(artists: List<ArtistIndex>)

    @Query("DELETE FROM artist_index WHERE scope = :scope AND name IN (:names)")
    suspend fun deleteArtists(names: List<String>, scope: Int)

    @Query(
        "SELECT indexGenre AS `key`, scope, COUNT(*) AS songCount, COUNT(DISTINCT indexArtist) AS artistCount, " +
            "COUNT(DISTINCT indexAlbum) AS albumCount FROM tracks WHERE indexGenre IN (:keys) GROUP BY indexGenre, scope"
    )
    suspend fun genreCounts(keys: List<String>): List<GenreCounts>

    @Query("SELECT artworkUri FROM tracks WHERE indexGenre = :key AND scope = :scope AND artworkUri IS NOT NULL LIMIT 1")
    suspend fun genreCover(key: String, scope: Int): String?

    /** The genre's most common spelling in the files, e.g. "Hard Rock" for "hard rock". */
    @Query("SELECT genre FROM tracks WHERE indexGenre = :key AND genre IS NOT NULL GROUP BY genre ORDER BY COUNT(*) DESC, genre LIMIT 1")
    suspend fun genreSpelling(key: String): String?

    @Upsert
    suspend fun upsertGenres(genres: List<GenreIndex>)

    @Query("DELETE FROM genre_index WHERE scope = :scope AND `key` IN (:keys)")
    suspend fun deleteGenres(keys: List<String>, scope: Int)

    @Query("SELECT * FROM album_index")
    suspend fun allAlbums(): List<AlbumIndex>

    @Query("SELECT * FROM artist_index")
    suspend fun allArtists(): List<ArtistIndex>

    @Query("SELECT * FROM genre_index")
    suspend fun allGenres(): List<GenreIndex>

    @Query("SELECT COUNT(*) FROM album_index")
    suspend fun albumCount(): Int

    @Query("SELECT COUNT(*) FROM artist_index")
    suspend fun artistCount(): Int

    @Query("SELECT COUNT(*) FROM genre_index")
    suspend fun genreCount(): Int
}

/** How many entries of a list start with each first character (for the A–Z bar). */
data class FirstChar(val ch: String, val count: Int)

data class TrackPlays(@Embedded val track: Track, val plays: Int)

/**
 * The library screens' data, straight from the database: songs, albums and artists page by page,
 * the rest as small lists (an album's songs, an artist's albums, the genres).
 *
 * [scopes] picks whose music is listed (Track.SCOPES: local folders, Drive, server; any combination). Albums,
 * artists and genres are summarised per scope; with several, the rows of a key are added together.
 */
@Dao
interface BrowseDao {
    // Songs tab: listed songs are the indexed, playable ones, by title.
    @Query("SELECT * FROM tracks WHERE indexTitle <> '' AND scope IN (:scopes) ORDER BY indexTitle, id")
    fun songs(scopes: List<Int>): PagingSource<Int, Track>

    @Query("SELECT COUNT(*) FROM tracks WHERE indexTitle <> '' AND scope IN (:scopes)")
    fun observeSongCount(scopes: List<Int>): Flow<Int>

    @Query("SELECT * FROM tracks WHERE indexTitle <> '' AND scope IN (:scopes) ORDER BY indexTitle, id LIMIT :limit OFFSET :offset")
    suspend fun songsFrom(offset: Int, limit: Int, scopes: List<Int>): List<Track>

    @Query("SELECT * FROM tracks WHERE indexTitle <> '' AND scope IN (:scopes) ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomSongs(limit: Int, scopes: List<Int>): List<Track>

    @Query(
        "SELECT substr(indexTitle, 1, 1) AS ch, COUNT(*) AS count FROM tracks " +
            "WHERE indexTitle <> '' AND scope IN (:scopes) GROUP BY ch ORDER BY ch"
    )
    fun songFirstChars(scopes: List<Int>): Flow<List<FirstChar>>

    // Albums tab
    @Query("SELECT $ALBUM_SUM FROM album_index WHERE scope IN (:scopes) GROUP BY `key` ORDER BY sortKey, `key`")
    fun albums(scopes: List<Int>): PagingSource<Int, AlbumIndex>

    @Query(
        "SELECT substr(sortKey, 2, 1) AS ch, COUNT(DISTINCT `key`) AS count FROM album_index WHERE scope IN (:scopes) " +
            "GROUP BY substr(sortKey, 1, 2) ORDER BY substr(sortKey, 1, 2)"
    )
    fun albumFirstChars(scopes: List<Int>): Flow<List<FirstChar>>

    @Query("SELECT $ALBUM_SUM FROM album_index WHERE `key` = :key AND scope IN (:scopes) GROUP BY `key`")
    fun observeAlbum(key: String, scopes: List<Int>): Flow<AlbumIndex?>

    @Query("SELECT * FROM tracks WHERE indexAlbum = :key AND scope IN (:scopes)")
    fun observeAlbumTracks(key: String, scopes: List<Int>): Flow<List<Track>>

    // Artists tab
    @Query("SELECT $ARTIST_SUM FROM artist_index WHERE scope IN (:scopes) GROUP BY name ORDER BY sortKey, name")
    fun artists(scopes: List<Int>): PagingSource<Int, ArtistIndex>

    @Query(
        "SELECT substr(sortKey, 2, 1) AS ch, COUNT(DISTINCT name) AS count FROM artist_index WHERE scope IN (:scopes) " +
            "GROUP BY substr(sortKey, 1, 2) ORDER BY substr(sortKey, 1, 2)"
    )
    fun artistFirstChars(scopes: List<Int>): Flow<List<FirstChar>>

    @Query("SELECT $ARTIST_SUM FROM artist_index WHERE name = :name AND scope IN (:scopes) GROUP BY name")
    fun observeArtist(name: String, scopes: List<Int>): Flow<ArtistIndex?>

    /** The artist's albums, newest first. */
    @Query(
        "SELECT $ALBUM_SUM FROM album_index WHERE artist = :name AND scope IN (:scopes) GROUP BY `key` " +
            "ORDER BY year IS NULL, year DESC, sortKey"
    )
    fun observeArtistAlbums(name: String, scopes: List<Int>): Flow<List<AlbumIndex>>

    /** The artist's songs by album, then disc and track number. */
    @Query(
        "SELECT * FROM tracks WHERE indexArtist = :name AND scope IN (:scopes) " +
            "ORDER BY indexAlbum, IFNULL(discNumber, 0), IFNULL(trackNumber, 2147483647), indexTitle"
    )
    fun observeArtistTracks(name: String, scopes: List<Int>): Flow<List<Track>>

    /** The artist's most played songs (counted plays). */
    @Query(
        "SELECT tracks.*, COUNT(play_history.id) AS plays FROM tracks " +
            "JOIN play_history ON play_history.trackId = tracks.id AND play_history.counted = 1 " +
            "WHERE tracks.indexArtist = :name AND tracks.scope IN (:scopes) GROUP BY tracks.id ORDER BY plays DESC, tracks.indexTitle LIMIT :limit"
    )
    fun observeArtistTop(name: String, limit: Int, scopes: List<Int>): Flow<List<TrackPlays>>

    // Genres tab
    @Query("SELECT $GENRE_SUM FROM genre_index WHERE scope IN (:scopes) GROUP BY `key` ORDER BY sortKey")
    fun observeGenres(scopes: List<Int>): Flow<List<GenreIndex>>

    @Query("SELECT $GENRE_SUM FROM genre_index WHERE `key` = :key AND scope IN (:scopes) GROUP BY `key`")
    fun observeGenre(key: String, scopes: List<Int>): Flow<GenreIndex?>

    /** Albums with at least one song of the genre. */
    @Query(
        "SELECT $ALBUM_SUM FROM album_index WHERE scope IN (:scopes) AND `key` IN " +
            "(SELECT DISTINCT indexAlbum FROM tracks WHERE indexGenre = :genre AND scope IN (:scopes)) GROUP BY `key` ORDER BY sortKey, `key`"
    )
    fun genreAlbums(genre: String, scopes: List<Int>): PagingSource<Int, AlbumIndex>

    @Query("SELECT * FROM tracks WHERE indexAlbum = :album AND indexGenre = :genre AND scope IN (:scopes)")
    fun observeAlbumTracksInGenre(album: String, genre: String, scopes: List<Int>): Flow<List<Track>>

    /** The genre's songs album by album (for Play), at most [limit]. */
    @Query(
        "SELECT tracks.* FROM tracks JOIN album_index ON album_index.`key` = tracks.indexAlbum AND album_index.scope = tracks.scope " +
            "WHERE tracks.indexGenre = :genre AND tracks.scope IN (:scopes) " +
            "ORDER BY album_index.sortKey, tracks.indexAlbum, IFNULL(discNumber, 0), IFNULL(trackNumber, 2147483647) LIMIT :limit"
    )
    suspend fun genreTracks(genre: String, limit: Int, scopes: List<Int>): List<Track>

    @Query("SELECT * FROM tracks WHERE indexGenre = :genre AND scope IN (:scopes) ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomGenreTracks(genre: String, limit: Int, scopes: List<Int>): List<Track>

    @Query("SELECT DISTINCT name FROM artist_index WHERE name IN (:names)")
    suspend fun existingArtists(names: List<String>): List<String>

    /** Listed songs per source ("drive", "saf"…). */
    @Query("SELECT source, COUNT(*) AS count FROM tracks WHERE durationMs >= 0 GROUP BY source")
    fun observeSourceCounts(): Flow<List<SourceCount>>

    /**
     * The albums of the newest songs (added to the app; for Navidrome, to the server), newest
     * first, one row per song: the first row of an album is when its newest song came. The index
     * is named because SQLite would otherwise pick the scope one and sort the whole table.
     */
    @Query(
        "SELECT indexAlbum AS `key`, scope FROM tracks INDEXED BY index_tracks_dateAdded " +
            "WHERE indexAlbum <> '' AND durationMs >= 0 AND scope IN (:scopes) ORDER BY dateAdded DESC LIMIT :limit"
    )
    suspend fun newestSongAlbums(scopes: List<Int>, limit: Int): List<AddedAlbum>

    @Query("SELECT $ALBUM_SUM FROM album_index WHERE `key` IN (:keys) GROUP BY `key`")
    suspend fun albumsByKeys(keys: List<String>): List<AlbumIndex>

    /** The album index named: SQLite would otherwise pick the scope one and read most of the table. */
    @Query("SELECT * FROM tracks INDEXED BY index_tracks_indexAlbum WHERE indexAlbum IN (:keys) AND scope IN (:scopes)")
    suspend fun tracksOfAlbums(keys: List<String>, scopes: List<Int>): List<Track>

    @Query("SELECT $ALBUM_SUM FROM album_index WHERE scope IN (:scopes) GROUP BY `key` ORDER BY sortKey, `key` LIMIT :limit OFFSET :offset")
    suspend fun albumsFrom(offset: Int, limit: Int, scopes: List<Int>): List<AlbumIndex>

    /** Songs A–Z from the given one on (inclusive). */
    @Query(
        "SELECT * FROM tracks WHERE indexTitle <> '' AND scope IN (:scopes) AND (indexTitle > :title OR (indexTitle = :title AND id >= :id)) " +
            "ORDER BY indexTitle, id LIMIT :limit"
    )
    suspend fun songsFromTitle(title: String, id: String, limit: Int, scopes: List<Int>): List<Track>

    @Query("SELECT * FROM tracks WHERE indexArtist IN (:names) AND scope IN (:scopes) ORDER BY indexTitle LIMIT :limit")
    suspend fun songsOfArtists(names: List<String>, limit: Int, scopes: List<Int>): List<Track>

    @Query("SELECT * FROM tracks WHERE indexAlbum IN (:keys) AND scope IN (:scopes) ORDER BY indexTitle LIMIT :limit")
    suspend fun songsOfAlbums(keys: List<String>, limit: Int, scopes: List<Int>): List<Track>

    /** Counted plays per listed artist, most played first. */
    @Query(
        "SELECT tracks.indexArtist AS name, COUNT(*) AS count FROM play_history JOIN tracks ON tracks.id = play_history.trackId " +
            "WHERE play_history.counted = 1 AND tracks.indexArtist <> '' GROUP BY tracks.indexArtist ORDER BY count DESC LIMIT :limit"
    )
    fun observeArtistPlayCounts(limit: Int): Flow<List<NameCount>>

    // Search box: [match] is a full-text query from SearchText.match.
    @Query(
        // The full-text match once, as a subquery: joined, SQLite may walk every song of one source in title order
        // and run the match per song (minutes on a large server library).
        "SELECT * FROM tracks WHERE rowid IN (SELECT docid FROM tracks_fts WHERE tracks_fts MATCH :match) " +
            "AND indexTitle <> '' AND scope IN (:scopes) ORDER BY indexTitle LIMIT :limit"
    )
    suspend fun searchSongs(match: String, limit: Int, scopes: List<Int>): List<Track>

    @Query(
        "SELECT $ALBUM_SUM FROM album_index WHERE rowid IN (SELECT docid FROM album_fts WHERE album_fts MATCH :match) " +
            "AND scope IN (:scopes) GROUP BY `key` ORDER BY sortKey LIMIT :limit"
    )
    suspend fun searchAlbums(match: String, limit: Int, scopes: List<Int>): List<AlbumIndex>

    @Query(
        "SELECT $ARTIST_SUM FROM artist_index WHERE rowid IN (SELECT docid FROM artist_fts WHERE artist_fts MATCH :match) " +
            "AND scope IN (:scopes) GROUP BY name ORDER BY sortKey LIMIT :limit"
    )
    suspend fun searchArtists(match: String, limit: Int, scopes: List<Int>): List<ArtistIndex>

    // The scopes an album, artist or genre has songs in (detail pages fall back to these).
    @Query("SELECT scope FROM album_index WHERE `key` = :key")
    fun observeAlbumScopes(key: String): Flow<List<Int>>

    @Query("SELECT scope FROM artist_index WHERE name = :name")
    fun observeArtistScopes(name: String): Flow<List<Int>>

    @Query("SELECT scope FROM genre_index WHERE `key` = :key")
    fun observeGenreScopes(key: String): Flow<List<Int>>

    companion object {
        /** One album row from its rows in the chosen scopes: counts added up; the own cover (file:, https:) before the server's (subsonic:). */
        const val ALBUM_SUM = "`key`, MIN(scope) AS scope, title, artist, MAX(year) AS year, MIN(artworkUri) AS artworkUri, " +
            "SUM(trackCount) AS trackCount, SUM(durationMs) AS durationMs, MIN(sortKey) AS sortKey"
        const val ARTIST_SUM = "name, MIN(scope) AS scope, SUM(trackCount) AS trackCount, SUM(albumCount) AS albumCount, MIN(sortKey) AS sortKey"
        const val GENRE_SUM = "`key`, MIN(scope) AS scope, name, SUM(songCount) AS songCount, SUM(artistCount) AS artistCount, " +
            "SUM(albumCount) AS albumCount, MIN(sortKey) AS sortKey, MIN(artworkUri) AS artworkUri"
    }
}
