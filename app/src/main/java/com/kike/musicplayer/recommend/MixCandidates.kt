package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.getByIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The songs the [Recommender] works with: the ones played or favourited, from any source (they
 * show the taste), plus the songs mixes may pick from, in the sources switched on ([scopes]).
 * Up to [FULL_LIBRARY_LIMIT] songs there are taken whole; more (a big music server) are narrowed
 * to everything by the most played artists and a random sample of the rest for genre, decade and
 * fresh picks.
 */
object MixCandidates {
    const val FULL_LIBRARY_LIMIT = 20_000
    private const val TOP_ARTISTS = 40
    private const val TOP_ARTIST_SONGS = 6_000
    private const val SAMPLE = 8_000

    suspend fun load(db: AppDatabase, history: List<PlayHistory>, favourites: Set<String>, scopes: List<Int>): List<Track> =
        withContext(Dispatchers.IO) {
            val tracks = db.tracks()
            val known = tracks.getByIds(history.mapTo(HashSet()) { it.trackId } + favourites)
            if (tracks.countPlayable(scopes) <= FULL_LIBRARY_LIMIT) return@withContext (known + tracks.getAll(scopes)).distinctBy { it.id }
            val browse = db.browse()
            val topArtists = known.filter { it.indexArtist.isNotEmpty() }.groupingBy { it.indexArtist }.eachCount()
                .entries.sortedByDescending { it.value }.take(TOP_ARTISTS).map { it.key }
            val ofTopArtists = if (topArtists.isEmpty()) emptyList() else browse.songsOfArtists(topArtists, TOP_ARTIST_SONGS, scopes)
            (known + ofTopArtists + browse.randomSongs(SAMPLE, scopes)).distinctBy { it.id }
        }
}
