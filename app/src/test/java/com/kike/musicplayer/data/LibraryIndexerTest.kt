package com.kike.musicplayer.data

import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.recommend.UNKNOWN_GENRE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexerTest {
    private fun track(
        id: String, title: String = "T$id", artist: String? = "Måns Zelmerlöw", album: String? = "Más Guapa",
        albumArtist: String? = null, track: Int? = null, year: Int? = null, art: String? = null,
        genre: String? = null, duration: Long = 200_000,
    ) = Track(
        id = id, source = "drive", uri = "u", fileName = "$id.mp3", mimeType = null, sizeBytes = null, title = title,
        artist = artist, album = album, albumArtist = albumArtist, trackNumber = track, year = year,
        artworkUri = art, genre = genre, durationMs = duration,
    )

    @Test
    fun keysMatchTheInMemoryGrouping() {
        val t = track("1", albumArtist = "Varios", genre = "Pop Rock")
        val (album, artist, genre) = LibraryIndexer.keysOf(t, emptyMap())
        assertEquals(t.albumKey, album) // Kotlin lower-casing: "más guapa", not SQLite's ASCII-only lower()
        assertEquals("Varios", artist)
        assertEquals("pop rock", genre)
    }

    @Test
    fun sortTextSkipsLeadingSpacesAndPunctuation() {
        assertEquals("low rider", LibraryIndexer.sortText(" Low Rider"))
        assertEquals("heroes\"", LibraryIndexer.sortText("\"Heroes\""))
        assertEquals("angel", LibraryIndexer.sortText("‎¡Ángel"))
        assertEquals("...", LibraryIndexer.sortText("..."))
        assertEquals("5 years", LibraryIndexer.sortText("(5 Years)").removeSuffix(")"))
    }

    @Test
    fun scopeFollowsTheSource() {
        assertEquals(Track.SCOPE_SERVER, Track.scopeOf(Track.SOURCE_SUBSONIC))
        assertEquals(Track.SCOPE_OWN, Track.scopeOf(Track.SOURCE_DRIVE))
    }

    @Test
    fun genreFallsBackToArtistTagsThenUnknown() {
        val tags = mapOf("måns zelmerlöw" to listOf("swedish pop", "pop"))
        assertEquals("swedish pop", LibraryIndexer.keysOf(track("1"), tags).third)
        assertEquals(UNKNOWN_GENRE, LibraryIndexer.keysOf(track("2", artist = "Nobody"), tags).third)
    }

    @Test
    fun unplayableSongsAreNotListed() {
        assertEquals(Triple("", "", ""), LibraryIndexer.keysOf(track("1", duration = Track.DURATION_UNPLAYABLE), emptyMap()))
    }

    @Test
    fun albumTakesTitleFromFirstTrackAndFirstYearAndCover() {
        val tracks = listOf(
            track("b", track = 2, year = 2004, art = "art-2"),
            track("a", track = 1, art = null),
            track("c", track = 3, year = 2005, duration = 100_000),
        )
        val a = LibraryIndexer.albumOf("k", tracks)
        assertEquals("Más Guapa", a.title)
        assertEquals("Måns Zelmerlöw", a.artist)
        assertEquals(2004, a.year)
        assertEquals("art-2", a.artworkUri)
        assertEquals(3, a.trackCount)
        assertEquals(500_000, a.durationMs)
        assertEquals("0mas guapa", a.sortKey)
    }

    @Test
    fun unknownNamesSortLast() {
        val unknown = LibraryIndexer.sortKey(LibraryIndexer.UNKNOWN_ALBUM, LibraryIndexer.UNKNOWN_ALBUM)
        assertTrue(unknown > LibraryIndexer.sortKey("Zzz", LibraryIndexer.UNKNOWN_ALBUM))
        assertEquals("Unknown genre", LibraryIndexer.genreName(UNKNOWN_GENRE, null))
        assertEquals("Hard Rock", LibraryIndexer.genreName("hard rock", "Hard Rock"))
        assertEquals("Swedish Pop", LibraryIndexer.genreName("swedish pop", null))
    }
}
