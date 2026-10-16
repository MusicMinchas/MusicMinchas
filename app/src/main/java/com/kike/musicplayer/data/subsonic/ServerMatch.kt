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

    /** The server's copy of a device or Drive song: same artist and title (the same album preferred). */
    suspend fun serverCopy(track: Track): Track? {
        val artist = track.artist ?: track.albumArtist ?: return null
        val match = SearchText.match("$artist ${track.title}") ?: return null
        val wantedArtists = listOfNotNull(track.artist, track.albumArtist).mapTo(HashSet(), TrackMatcher::basic)
        val wantedTitle = TrackMatcher.basic(track.title)
        val candidates = db.browse().searchSongs(match, CANDIDATES, listOf(Track.SCOPE_SERVER)).filter { c ->
            TrackMatcher.basic(c.title) == wantedTitle &&
                listOfNotNull(c.artist, c.albumArtist).any { TrackMatcher.basic(it) in wantedArtists }
        }
        val album = track.album?.let(TrackMatcher::basic)
        return candidates.firstOrNull { album != null && it.album?.let(TrackMatcher::basic) == album } ?: candidates.firstOrNull()
    }

    private companion object {
        const val CANDIDATES = 50
    }
}
