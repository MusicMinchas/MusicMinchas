package com.kike.musicplayer.data.subsonic

import com.kike.musicplayer.data.SearchText
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.lastfm.TrackMatcher

/** Finds the Subsonic server's copy of a song, for play reports and playlists sent to the server. */
class ServerMatch(private val db: AppDatabase) {
    /** The server's song id for [track]: its own for a server song, else the copy with the same artist and title. */
    suspend fun serverSongId(track: Track): String? =
        if (track.source == Track.SOURCE_SUBSONIC) track.uri.removePrefix(SubsonicUrls.PREFIX)
        else serverCopy(track)?.uri?.removePrefix(SubsonicUrls.PREFIX)

    /**
     * [serverSongId], remembered while the app runs: favourites are matched on every sync, and
     * hundreds of them shouldn't be looked up each time. Songs the server got since are found after
     * a restart.
     */
    suspend fun serverSongIdCached(track: Track): String? {
        if (track.source == Track.SOURCE_SUBSONIC) return serverSongId(track)
        cache[track.id]?.let { return it.ifEmpty { null } }
        return serverSongId(track).also { cache[track.id] = it.orEmpty() }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The server's copy of a device or Drive song: the same song by the Last.fm matching rules (same
     * artist; same title ignoring case, accents and notes like "(Remastered)" or "(feat. …)", while
     * live, demo and remix versions stay apart). The identical title first, then the same album.
     */
    suspend fun serverCopy(track: Track): Track? {
        val artist = track.artist ?: track.albumArtist ?: return null
        val match = SearchText.match("$artist ${TrackMatcher.looseTitle(track.title)}") ?: return null
        val wantedArtists = listOfNotNull(track.artist, track.albumArtist).mapTo(HashSet(), TrackMatcher::looseArtist)
        val wantedTitle = TrackMatcher.looseTitle(track.title)
        val candidates = db.browse().searchSongs(match, CANDIDATES, listOf(Track.SCOPE_SERVER)).filter { c ->
            TrackMatcher.looseTitle(c.title) == wantedTitle &&
                listOfNotNull(c.artist, c.albumArtist).any { TrackMatcher.looseArtist(it) in wantedArtists }
        }
        val exactTitle = TrackMatcher.basic(track.title)
        val album = track.album?.let(TrackMatcher::basic)
        return candidates.sortedWith(
            compareByDescending<Track> { TrackMatcher.basic(it.title) == exactTitle }.thenByDescending { album != null && it.album?.let(TrackMatcher::basic) == album }
        ).firstOrNull()
    }

    /**
     * The user's own copy (device or Drive) of a server song: the same song by the Last.fm matching
     * rules, preferring local files over Drive, identical title first, then the same album.
     */
    suspend fun ownCopy(track: Track): Track? {
        if (track.source != Track.SOURCE_SUBSONIC) return track
        val artist = track.artist ?: track.albumArtist ?: return null
        val match = SearchText.match("$artist ${TrackMatcher.looseTitle(track.title)}") ?: return null
        val wantedArtists = listOfNotNull(track.artist, track.albumArtist).mapTo(HashSet(), TrackMatcher::looseArtist)
        val wantedTitle = TrackMatcher.looseTitle(track.title)
        val candidates = db.browse().searchSongs(match, CANDIDATES, Track.OWN_SCOPES).filter { c ->
            TrackMatcher.looseTitle(c.title) == wantedTitle &&
                listOfNotNull(c.artist, c.albumArtist).any { TrackMatcher.looseArtist(it) in wantedArtists }
        }
        val exactTitle = TrackMatcher.basic(track.title)
        val album = track.album?.let(TrackMatcher::basic)
        return candidates.sortedWith(
            compareByDescending<Track> { it.scope == Track.SCOPE_LOCAL }
                .thenByDescending { TrackMatcher.basic(it.title) == exactTitle }
                .thenByDescending { album != null && it.album?.let(TrackMatcher::basic) == album }
        ).firstOrNull()
    }

    private companion object {
        const val CANDIDATES = 100
    }
}
