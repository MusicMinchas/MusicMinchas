package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.getByIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The songs the [Recommender] chooses from. A library of up to [FULL_LIBRARY_LIMIT] songs is
 * taken whole; a larger one (a big music server) is narrowed to what the mixes draw on: the songs
 * played or favourited, everything by the most played of their artists, and a random sample of
 * the rest for genre, decade and fresh picks.
 */
object MixCandidates {
    const val FULL_LIBRARY_LIMIT = 20_000
    private const val TOP_ARTISTS = 40
    private const val TOP_ARTIST_SONGS = 6_000
    private const val SAMPLE = 8_000

    suspend fun load(db: AppDatabase, history: List<PlayHistory>, favourites: Set<String>): List<Track> = withContext(Dispatchers.IO) {
        val tracks = db.tracks()
        if (tracks.countPlayable() <= FULL_LIBRARY_LIMIT) return@withContext tracks.getAll()
        val browse = db.browse()
        val known = tracks.getByIds(history.mapTo(HashSet()) { it.trackId } + favourites)
        val topArtists = known.filter { it.indexArtist.isNotEmpty() }.groupingBy { it.indexArtist }.eachCount()
            .entries.sortedByDescending { it.value }.take(TOP_ARTISTS).map { it.key }
        val ofTopArtists = if (topArtists.isEmpty()) emptyList() else browse.songsOfArtists(topArtists, TOP_ARTIST_SONGS)
        (known + ofTopArtists + browse.randomSongs(SAMPLE)).distinctBy { it.id }
    }
}
