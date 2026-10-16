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
 * The user's own sources (device, Drive) and the server are loaded separately: each is taken
 * whole up to [FULL_LIBRARY_LIMIT] songs, so the own copy of a song is always there to be preferred
 * over the server's; a larger one (a big music server) is narrowed to everything by the most
 * played artists and a random sample of the rest for genre, decade and fresh picks.
 */
object MixCandidates {
    const val FULL_LIBRARY_LIMIT = 20_000
    private const val TOP_ARTISTS = 40
    private const val TOP_ARTIST_SONGS = 6_000
    private const val SAMPLE = 8_000

    suspend fun load(db: AppDatabase, history: List<PlayHistory>, favourites: Set<String>, scopes: List<Int>): List<Track> =
        withContext(Dispatchers.IO) {
            val known = db.tracks().getByIds(history.mapTo(HashSet()) { it.trackId } + favourites)
            val parts = scopes.partition { it != Track.SCOPE_SERVER }.toList().filter { it.isNotEmpty() }
            (known + parts.flatMap { part -> songsOf(db, part, known) }).distinctBy { it.id }
        }

    private suspend fun songsOf(db: AppDatabase, scopes: List<Int>, known: List<Track>): List<Track> {
        val tracks = db.tracks()
        if (tracks.countPlayable(scopes) <= FULL_LIBRARY_LIMIT) return tracks.getAll(scopes)
        val browse = db.browse()
        val topArtists = known.filter { it.indexArtist.isNotEmpty() }.groupingBy { it.indexArtist }.eachCount()
            .entries.sortedByDescending { it.value }.take(TOP_ARTISTS).map { it.key }
        val ofTopArtists = if (topArtists.isEmpty()) emptyList() else browse.songsOfArtists(topArtists, TOP_ARTIST_SONGS, scopes)
        return ofTopArtists + browse.randomSongs(SAMPLE, scopes)
    }
}
