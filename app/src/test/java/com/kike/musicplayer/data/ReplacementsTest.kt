package com.kike.musicplayer.data

import com.kike.musicplayer.data.db.ReplacedTrack
import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplacementsTest {
    private fun old(id: String, title: String, album: String = "Fallen", n: Int? = null, disc: Int? = null, artist: String = "Evanescence") =
        ReplacedTrack(
            oldId = id, source = Track.SOURCE_DRIVE, artist = artist, albumArtist = null, album = album, title = title,
            trackNumber = n, discNumber = disc, localUri = null, removedAt = 0,
        )

    private fun new(id: String, title: String, album: String = "Fallen", n: Int? = null, disc: Int? = null, artist: String = "Evanescence") =
        Track(
            id = id, source = Track.SOURCE_DRIVE, uri = "u", fileName = "$id.flac", mimeType = "audio/flac", sizeBytes = 1,
            title = title, artist = artist, album = album, trackNumber = n, discNumber = disc,
        )

    private fun pairs(removed: List<ReplacedTrack>, added: List<Track>) =
        Replacements.pair(removed, added).associate { (o, n) -> o.oldId to n.id }

    @Test
    fun `an mp3 re-ripped as flac keeps its identity`() {
        assertEquals(
            mapOf("mp3-1" to "flac-1", "mp3-2" to "flac-2"),
            pairs(
                listOf(old("mp3-1", "Going Under", n = 1), old("mp3-2", "Bring Me To Life", n = 2)),
                listOf(new("flac-2", "Bring Me to Life", n = 2), new("flac-1", "Going Under", n = 1), new("other", "Haunted", n = 5)),
            ),
        )
    }

    @Test
    fun `track number pairs titles spelled differently on the same album`() {
        assertEquals(
            mapOf("mp3-13" to "flac-13"),
            pairs(listOf(old("mp3-13", "Missing (Bonus)", n = 13)), listOf(new("flac-13", "Missing", n = 13))),
        )
        // ...but not across discs.
        assertEquals(
            emptyMap<String, String>(),
            pairs(listOf(old("d1", "Intro A", n = 1, disc = 1)), listOf(new("d2", "Outro B", n = 1, disc = 2))),
        )
    }

    @Test
    fun `title alone pairs a renamed album, when unambiguous`() {
        assertEquals(
            mapOf("mp3" to "flac"),
            pairs(listOf(old("mp3", "Battery", album = "Master of Puppets")), listOf(new("flac", "Battery (Remastered)", album = "Master of Puppets (Remastered)"))),
        )
        assertEquals(
            emptyMap<String, String>(),
            pairs(
                listOf(old("mp3", "Intro", album = "A")),
                listOf(new("x", "Intro", album = "B"), new("y", "Intro", album = "C")),
            ),
        )
    }

    @Test
    fun `different artists or songs are never paired`() {
        assertEquals(
            emptyMap<String, String>(),
            pairs(listOf(old("mp3", "Going Under", n = 1)), listOf(new("flac", "Going Under", n = 1, artist = "Someone Else"))),
        )
        assertEquals(
            emptyMap<String, String>(),
            pairs(listOf(old("mp3", "Going Under", n = 1)), listOf(new("flac", "Haunted", n = 5))),
        )
    }
}
