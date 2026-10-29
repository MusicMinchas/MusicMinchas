package com.kike.musicplayer.ui

import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferOwnCopiesTest {
    private fun track(id: String, title: String, source: String) = Track(
        id = id, source = source, uri = "u", fileName = "f", mimeType = null, sizeBytes = null,
        title = title, artist = "Nightwish", album = "Dark Passion Play", durationMs = 200_000,
    )

    @Test
    fun eachSongOnceOwnCopyFirstServerOnlyKept() {
        val tracks = listOf(
            track("d1", "The Poet And The Pendulum", Track.SOURCE_DRIVE),
            track("s1", "The Poet and the Pendulum", Track.SOURCE_SUBSONIC),
            track("d2", "Bye Bye Beautiful", Track.SOURCE_DRIVE),
            track("s2", "Bye Bye Beautiful (Remastered)", Track.SOURCE_SUBSONIC),
            track("s3", "Bye Bye Beautiful (Live)", Track.SOURCE_SUBSONIC), // another recording
            track("s4", "Bonus Track", Track.SOURCE_SUBSONIC), // only on the server
        )
        assertEquals(listOf("d1", "d2", "s3", "s4"), preferOwnCopies(tracks).map { it.id })
    }

    @Test
    fun oneSourceOnlyIsLeftAlone() {
        val server = listOf(track("s1", "Amaranth", Track.SOURCE_SUBSONIC), track("s2", "Amaranth", Track.SOURCE_SUBSONIC))
        assertEquals(server, preferOwnCopies(server))
    }

    @Test
    fun favouritesDeduplicationPreservesDriveCopyOverServerCopy() {
        val favs = listOf(
            track("drive:1", "Ghost River", Track.SOURCE_DRIVE),
            track("subsonic:1", "Ghost River", Track.SOURCE_SUBSONIC),
            track("subsonic:2", "Ghost River (Live)", Track.SOURCE_SUBSONIC),
            track("subsonic:3", "Unique Song", Track.SOURCE_SUBSONIC),
        )
        val deduplicated = preferOwnCopies(favs)
        assertEquals(listOf("drive:1", "subsonic:2", "subsonic:3"), deduplicated.map { it.id })
    }
}
