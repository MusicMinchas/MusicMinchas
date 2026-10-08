package com.kike.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIdsChunk(ids: List<String>): List<Track>

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
    suspend fun save(trackIds: List<String>, state: SessionState) {
        clearItems()
        insertItems(trackIds.mapIndexed { i, id -> QueueItem(i, id) })
        insertState(state)
    }
}
