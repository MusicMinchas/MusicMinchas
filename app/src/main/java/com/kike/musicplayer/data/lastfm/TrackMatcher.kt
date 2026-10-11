package com.kike.musicplayer.data.lastfm

import com.kike.musicplayer.data.db.Track
import java.text.Normalizer

/**
 * Finds the library song a Last.fm scrobble is about: by MusicBrainz recording ID when both have
 * one, else by artist and title, compared without case, accents and punctuation, then once more
 * without suffixes such as "(feat. …)" or "- Remastered 2011". "Live" and "Remix" versions are
 * different recordings and stay distinct. When a song is on several albums, the scrobble's album
 * picks one.
 */
class TrackMatcher(tracks: List<Track>) {
    private val byMbid = HashMap<String, Track>()
    private val exact = HashMap<String, MutableList<Track>>()
    private val loose = HashMap<String, MutableList<Track>>()

    init {
        for (t in tracks) {
            if (t.durationMs < 0) continue // unplayable (DRM)
            t.mbRecordingId?.let { byMbid.putIfAbsent(it, t) }
            for (artist in listOfNotNull(t.artist, t.albumArtist).distinct()) {
                exact.getOrPut(key(basic(artist), basic(t.title))) { mutableListOf() } += t
                loose.getOrPut(key(looseArtist(artist), looseTitle(t.title))) { mutableListOf() } += t
            }
        }
    }

    fun match(artist: String, title: String, album: String?, mbid: String?): Track? {
        mbid?.let { byMbid[it] }?.let { return it }
        val candidates = exact[key(basic(artist), basic(title))]
            ?: loose[key(looseArtist(artist), looseTitle(title))]
            ?: return null
        val wanted = album?.let(::basic)
        return candidates.firstOrNull { wanted != null && it.album?.let(::basic) == wanted } ?: candidates.first()
    }

    companion object {
        private fun key(artist: String, title: String) = "$artist\u0001$title"

        /** Lower case, no accents, "&" as "and", punctuation as single spaces, no leading "the". */
        fun basic(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(MARKS, "")
                .replace("&", " and ")
                .replace(NON_WORD, " ")
                .trim()
                .removePrefix("the ")

        /** Without "(feat. …)", "[Remastered]", "- 2011 Remaster" and the like. */
        fun looseTitle(s: String): String = basic(
            s.replace(BRACKETED_EXTRA, "").replace(DASH_EXTRA, "")
        )

        /** Without a featured artist: "Artist feat. Someone" is "Artist". */
        fun looseArtist(s: String): String = basic(s.replace(FEATURING, ""))

        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private const val EXTRA_WORDS =
            "feat\\.?|ft\\.?|featuring|with|remaster(ed)?|bonus( track)?|explicit|clean|album version|" +
                "single version|radio edit|lp version|mono|stereo|deluxe( edition)?|\\d{4} (re)?master"
        private val BRACKETED_EXTRA = Regex("\\s*[(\\[][^)\\]]*\\b($EXTRA_WORDS)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
        private val DASH_EXTRA = Regex("\\s+-\\s+[^-]*\\b($EXTRA_WORDS)\\b.*$", RegexOption.IGNORE_CASE)
        private val FEATURING = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    }
}
