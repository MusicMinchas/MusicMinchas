package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.getByIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AutoplaySuggester {

    /**
     * Recommends a batch of [count] tracks similar to [currentTrack] and [recentTracks],
     * excluding any tracks already in [existingQueueIds].
     * Prefers own copies (local/drive) and favourite tracks where possible.
     */
    suspend fun suggestTracks(
        currentTrack: Track?,
        recentTracks: List<Track>,
        existingQueueIds: Set<String>,
        db: AppDatabase,
        scopes: List<Int>,
        count: Int = 8,
    ): List<Track> = withContext(Dispatchers.IO) {
        if (scopes.isEmpty()) return@withContext emptyList()

        val seedTracks = (listOfNotNull(currentTrack) + recentTracks).distinctBy { it.id }
        val seedArtists = seedTracks.mapNotNull {
            val a = it.indexArtist.ifEmpty { it.albumArtist ?: it.artist }
            a?.takeIf { s -> s.isNotBlank() }
        }.distinct()
        val seedGenres = seedTracks.mapNotNull {
            val g = it.indexGenre.ifEmpty { it.genre }
            g?.takeIf { s -> s.isNotBlank() }
        }.distinct()

        val browse = db.browse()
        val trackDao = db.tracks()
        val favDao = db.favourites()
        val favourites = favDao.allTrackIds().toSet()

        val result = mutableListOf<Track>()
        val excludedIds = existingQueueIds.toMutableSet()

        // 1. Same artist tracks (different songs by the same artists)
        if (seedArtists.isNotEmpty()) {
            val artistTracks = trackDao.byArtistNamesChunk(seedArtists)
                .filter { it.durationMs > 0 && it.scope in scopes && it.id !in excludedIds }
                .shuffled()
                .take(3)
            for (t in artistTracks) {
                result += t
                excludedIds += t.id
            }
        }

        // 2. Related artists (via shared MusicBrainz tags in artist_info)
        if (result.size < count && seedArtists.isNotEmpty()) {
            val allArtistInfo = db.artistInfo().all()
            val seedTags = seedArtists.flatMap { artistName ->
                allArtistInfo.find { it.name.equals(artistName, ignoreCase = true) }
                    ?.tags?.split(",")?.map { it.trim().lowercase() } ?: emptyList()
            }.filter { it.isNotBlank() }.toSet()

            if (seedTags.isNotEmpty()) {
                val relatedArtistNames = allArtistInfo
                    .filter { it.name !in seedArtists }
                    .mapNotNull { info ->
                        val tags = info.tags.split(",").map { it.trim().lowercase() }.toSet()
                        val overlap = tags.intersect(seedTags).size
                        if (overlap > 0) info.name to overlap else null
                    }
                    .sortedByDescending { it.second }
                    .take(10)
                    .map { it.first }

                if (relatedArtistNames.isNotEmpty()) {
                    val relatedTracks = trackDao.byArtistNamesChunk(relatedArtistNames)
                        .filter { it.durationMs > 0 && it.scope in scopes && it.id !in excludedIds }
                        .shuffled()
                        .take(count - result.size)
                    for (t in relatedTracks) {
                        result += t
                        excludedIds += t.id
                    }
                }
            }
        }

        // 3. Same genre tracks
        if (result.size < count && seedGenres.isNotEmpty()) {
            for (genre in seedGenres) {
                if (result.size >= count) break
                val genreTracks = browse.randomGenreTracks(genre, count - result.size, scopes)
                    .filter { it.durationMs > 0 && it.id !in excludedIds }
                for (t in genreTracks) {
                    result += t
                    excludedIds += t.id
                }
            }
        }

        // 4. Favourite tracks fallback (matching taste)
        if (result.size < count && favourites.isNotEmpty()) {
            val unqueuedFavIds = favourites.filter { it !in excludedIds }.shuffled().take(count - result.size)
            if (unqueuedFavIds.isNotEmpty()) {
                val favTracks = trackDao.getByIds(unqueuedFavIds)
                    .filter { it.durationMs > 0 && it.scope in scopes }
                for (t in favTracks) {
                    result += t
                    excludedIds += t.id
                }
            }
        }

        // 5. General random library fallback if library has few/no matches
        if (result.size < count) {
            val randomFallback = browse.randomSongs(count - result.size, scopes)
                .filter { it.durationMs > 0 && it.id !in excludedIds }
            for (t in randomFallback) {
                result += t
                excludedIds += t.id
            }
        }

        result.take(count)
    }
}
