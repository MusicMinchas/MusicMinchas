package com.kike.musicplayer.data.lastfm

import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.byArtistNames
import com.kike.musicplayer.data.db.byRecordingIds
import java.text.Normalizer

/**
 * Finds the library song a Last.fm scrobble is about: by MusicBrainz recording ID when both have
 * one, else by artist and title, compared without case, accents and punctuation, then once more
 * without suffixes such as "(feat. …)" or "- Remastered 2011". "Live" and "Remix" versions are
 * different recordings and stay distinct. When a song is on several albums, the scrobble's album
 * picks one. Last resorts, both within the same artist: on the same album, a library title equal to
 * the scrobble's once its trailing subtitle is dropped ("Uah lúa (Folla do Visgo)"); then a nearly
 * identical spelling ("Olla meu irmau" / "Olla meu irmao"), with the same live/remix/demo markers.
 * The user's own copy of a song (device, Drive) is preferred over the server's.
 */
class TrackMatcher(tracks: List<Track>) {
    private val byMbid = HashMap<String, Track>()
    private val exact = HashMap<String, MutableList<Track>>()
    private val loose = HashMap<String, MutableList<Track>>()
    private val byArtist = HashMap<String, MutableList<Track>>()

    init {
        // Own copies first, so they come first in every list below.
        for (t in tracks.sortedBy { it.fromServer }) {
            if (t.durationMs < 0) continue // unplayable (DRM)
            t.mbRecordingId?.let { byMbid.putIfAbsent(it, t) }
            for (artist in listOfNotNull(t.artist, t.albumArtist).distinct()) {
                exact.getOrPut(key(basic(artist), basic(t.title))) { mutableListOf() } += t
                loose.getOrPut(key(looseArtist(artist), looseTitle(t.title))) { mutableListOf() } += t
                byArtist.getOrPut(looseArtist(artist)) { mutableListOf() }.let { if (t !in it) it += t }
            }
        }
    }

    fun match(artist: String, title: String, album: String?, mbid: String?): Track? {
        mbid?.let { byMbid[it] }?.let { return it }
        val wanted = album?.let(::basic)
        val candidates = exact[key(basic(artist), basic(title))]
            ?: loose[key(looseArtist(artist), looseTitle(title))]
            ?: return lastResort(artist, title, wanted)
        val onAlbum = { t: Track -> wanted != null && t.album?.let(::basic) == wanted }
        return candidates.firstOrNull { !it.fromServer && onAlbum(it) } ?: candidates.firstOrNull { !it.fromServer }
            ?: candidates.firstOrNull(onAlbum) ?: candidates.first()
    }

    private fun lastResort(artist: String, title: String, album: String?): Track? {
        val tracks = byArtist[looseArtist(artist)] ?: return null
        val plain = basic(title)
        // Same album, and the library title has a subtitle the scrobble doesn't.
        if (album != null) {
            tracks.firstOrNull { it.album?.let(::basic) == album && basic(it.title.replace(TRAILING_SUBTITLE, "")) == plain }
                ?.let { return it }
        }
        // Nearly the same spelling: at most ~1 character in 10 different, same version markers.
        val wanted = compact(title)
        if (wanted.length < MIN_FUZZY_LENGTH) return null
        val markers = markers(plain)
        return tracks.asSequence()
            .filter { markers(basic(it.title)) == markers }
            .map { it to similarity(wanted, compact(it.title)) }
            .filter { it.second >= FUZZY_SIMILARITY }
            .sortedWith(
                compareBy<Pair<Track, Double>> { it.first.fromServer }.thenByDescending { it.second }
                    .thenByDescending { it.first.album?.let(::basic) == album }
            )
            .firstOrNull()?.first
    }

    companion object {
        private val Track.fromServer get() = source == Track.SOURCE_SUBSONIC

        /**
         * A matcher over just the library songs that can match these scrobbles: those by one of
         * their artists (however spelled) or with one of their MusicBrainz recording IDs, so a
         * large library is never loaded whole.
         */
        suspend fun forScrobbles(dao: TrackDao, artists: Collection<String>, recordingIds: Collection<String>): TrackMatcher {
            val wanted = artists.mapTo(HashSet()) { looseArtist(it) }
            val names = dao.artistPairs().flatMap { listOfNotNull(it.artist, it.albumArtist) }.distinct()
                .filter { looseArtist(it) in wanted }
            val tracks = dao.byArtistNames(names) + dao.byRecordingIds(recordingIds)
            return TrackMatcher(tracks.distinctBy { it.id })
        }

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

        /** Spelling-insensitive form: no spaces, no leading article, "pt" as "part". */
        private fun compact(s: String): String =
            basic(s).split(' ').map { if (it == "pt") "part" else it }
                .let { if (it.firstOrNull() == "a" || it.firstOrNull() == "an") it.drop(1) else it }
                .joinToString("")

        private fun markers(basicTitle: String) = basicTitle.split(' ').filterTo(HashSet()) { it in VERSION_MARKERS }

        /** 1 - edit distance / longer length. */
        private fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1).also { it[0] = i }
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
        }

        private const val MIN_FUZZY_LENGTH = 6
        private const val FUZZY_SIMILARITY = 0.9
        private val VERSION_MARKERS = setOf(
            "live", "remix", "mix", "demo", "acoustic", "rehearsal", "instrumental", "version", "edit",
            "unplugged", "video", "tapes", "session", "sessions", "orchestral", "radio",
        )
        private val TRAILING_SUBTITLE = Regex("\\s*[(\\[][^)\\]]*[)\\]]\\s*$")
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
