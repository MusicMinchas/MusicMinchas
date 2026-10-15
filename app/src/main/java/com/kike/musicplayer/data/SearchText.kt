package com.kike.musicplayer.data

import com.kike.musicplayer.data.db.BrowseDao
import com.kike.musicplayer.data.db.Track

/** What is typed in the search box, as a full-text query. */
object SearchText {
    private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Every word typed must start a word of the title ("lost holly" finds "Lost in Hollywood");
     * punctuation separates words and can't be mistaken for query syntax. Null when nothing
     * searchable was typed.
     */
    fun match(text: String): String? {
        val words = text.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }
}

/** Songs by title, artist or album, from the search tables (playlist picker, media browser). */
object SongSearch {
    private const val NAMES = 50

    /** Songs whose title, artist or album (as chosen) has words starting with those typed, A–Z, at most [limit]. */
    suspend fun find(browse: BrowseDao, text: String, limit: Int, scopes: List<Int>, bySong: Boolean = true, byArtist: Boolean = true, byAlbum: Boolean = true): List<Track> {
        val match = SearchText.match(text) ?: return emptyList()
        val found = LinkedHashMap<String, Track>()
        if (bySong) browse.searchSongs(match, limit, scopes).forEach { found[it.id] = it }
        if (byArtist) {
            val names = browse.searchArtists(match, NAMES, scopes).map { it.name }
            if (names.isNotEmpty()) browse.songsOfArtists(names, limit, scopes).forEach { found.putIfAbsent(it.id, it) }
        }
        if (byAlbum) {
            val keys = browse.searchAlbums(match, NAMES, scopes).map { it.key }
            if (keys.isNotEmpty()) browse.songsOfAlbums(keys, limit, scopes).forEach { found.putIfAbsent(it.id, it) }
        }
        return found.values.sortedBy { it.indexTitle }.take(limit)
    }
}

