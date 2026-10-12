package com.kike.musicplayer.data

import com.kike.musicplayer.data.db.ReplacedTrack
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.lastfm.TrackMatcher

/**
 * Pairs songs a sync removed with songs it added that are the same song in a new file (an MP3
 * re-ripped as FLAC/ALAC, a corrected file uploaded again), so plays, favourites and downloads
 * carry over. Compared, within the same (album) artist, in this order: album and title; album and
 * disc/track number (titles spelled differently); title alone when only one new song has it.
 */
object Replacements {
    fun pair(removed: List<ReplacedTrack>, added: List<Track>): List<Pair<ReplacedTrack, Track>> {
        val free = added.toMutableList()
        val pairs = mutableListOf<Pair<ReplacedTrack, Track>>()

        fun take(old: ReplacedTrack, same: (Track) -> Boolean): Boolean {
            val candidates = free.filter { artistKey(it.albumArtist, it.artist) == artistKey(old.albumArtist, old.artist) && same(it) }
            val match = candidates.singleOrNull() ?: return false
            free -= match
            pairs += old to match
            return true
        }

        val unpaired = removed.filterTo(mutableListOf()) { old ->
            !take(old) { key(it.album) == key(old.album) && key(it.title) == key(old.title) }
        }
        unpaired.retainAll { old ->
            old.trackNumber == null ||
                !take(old) { key(it.album) == key(old.album) && it.trackNumber == old.trackNumber && (it.discNumber ?: 1) == (old.discNumber ?: 1) }
        }
        unpaired.forEach { old -> take(old) { TrackMatcher.looseTitle(it.title) == TrackMatcher.looseTitle(old.title) } }
        return pairs
    }

    private fun key(s: String?) = s?.let(TrackMatcher::basic).orEmpty()

    private fun artistKey(albumArtist: String?, artist: String?) = key(albumArtist ?: artist)
}
