package com.kike.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    // @Transaction: a large library spans several CursorWindows; without a read transaction a
    // concurrent write between window refills makes rows vanish ("Couldn't read row N").
    @Transaction
    @Query("SELECT * FROM tracks WHERE durationMs >= 0 ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Track>>

    @Transaction
    @Query("SELECT * FROM tracks WHERE durationMs >= 0 ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<Track>

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

    @Query("SELECT COUNT(*) FROM tracks WHERE downloadState = ${Track.DOWNLOAD_QUEUED}")
    suspend fun countDownloadQueued(): Int

    @Query("SELECT * FROM tracks WHERE downloadState = ${Track.DOWNLOADED} AND localUri IS NOT NULL")
    suspend fun downloaded(): List<Track>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIdsChunk(ids: List<String>): List<Track>

    /** Every artist and album artist in the library (for the genre lookup). */
    @Query(
        "SELECT DISTINCT artist FROM tracks WHERE artist IS NOT NULL AND durationMs >= 0 " +
            "UNION SELECT DISTINCT albumArtist FROM tracks WHERE albumArtist IS NOT NULL AND durationMs >= 0"
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
            "AND (artist IS NULL OR album IS NULL OR artworkUri IS NULL)"
    )
    suspend fun requeueIncomplete(): Int

    /**
     * Tracks without a duration were never read successfully (or are MP3s read before durations were
     * estimated); queue them for another tag read.
     */
    @Query("UPDATE tracks SET metadataState = ${Track.META_NEW} WHERE durationMs = 0 AND metadataState != ${Track.META_NEW}")
    suspend fun requeueUnread(): Int

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
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(scrobbles: List<LastFmScrobble>)

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
    @Query("SELECT * FROM tracks WHERE dateAdded >= :since AND metadataState != ${Track.META_NEW} AND durationMs >= 0")
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

    /** Everything the app knows about a song by its id moves to the new file's id. */
    @Transaction
    suspend fun moveSong(oldId: String, newId: String) {
        movePlays(oldId, newId)
        copyFavourite(oldId, newId)
        deleteFavourite(oldId)
        moveScrobbles(oldId, newId)
        moveQueue(oldId, newId)
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

    @Query("SELECT * FROM artist_info WHERE name = :name")
    suspend fun get(name: String): ArtistInfo?

    @Upsert
    suspend fun upsert(info: ArtistInfo)
}
