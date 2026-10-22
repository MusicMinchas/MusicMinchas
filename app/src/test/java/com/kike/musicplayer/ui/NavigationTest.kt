package com.kike.musicplayer.ui

import com.kike.musicplayer.data.catalog.CatalogAlbum
import com.kike.musicplayer.data.catalog.CatalogPlaylist
import com.kike.musicplayer.data.catalog.CatalogTrack
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.nebula.NebulaUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {

    @Test
    fun navigationOverloadsMaintainBackwardCompatibility() {
        var calledArtist: List<String?>? = null
        var calledArtistTrack: Track? = null
        var calledAlbumKey: String? = null
        var calledAlbumTrack: Track? = null

        val nav = Navigation(
            openArtist = { names, track ->
                calledArtist = names
                calledArtistTrack = track
            },
            openAlbum = { key, track ->
                calledAlbumKey = key
                calledAlbumTrack = track
            },
            track = { null },
        )

        // 1-argument overloads
        nav.openArtist(listOf("Queen"))
        assertEquals(listOf("Queen"), calledArtist)
        assertNull(calledArtistTrack)

        nav.openAlbum("sheer heart attack|queen")
        assertEquals("sheer heart attack|queen", calledAlbumKey)
        assertNull(calledAlbumTrack)

        // 2-argument overloads with track
        val sampleTrack = Track(
            id = "nebula:dz:123",
            source = Track.SOURCE_NEBULA,
            uri = NebulaUrls.uriFor("Queen", "Sheer Heart Attack", "Stone Cold Crazy", "dz:123", "dz:382524"),
            fileName = "Stone Cold Crazy.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = "Stone Cold Crazy",
            artist = "Queen",
            album = "Sheer Heart Attack",
            mbReleaseId = "dz:382524",
            scope = Track.SCOPE_STREAM,
        )

        nav.openArtist(listOf("Queen"), sampleTrack)
        assertEquals(sampleTrack, calledArtistTrack)

        nav.openAlbum("sheer heart attack|queen", sampleTrack)
        assertEquals(sampleTrack, calledAlbumTrack)
    }

    @Test
    fun catalogAlbumConstructedCorrectlyFromTrackMetadata() {
        val catTrack = CatalogTrack(
            id = "dz:123",
            title = "Stone Cold Crazy",
            artist = "Queen",
            album = "Sheer Heart Attack",
            albumArtist = "Queen",
            trackNumber = 8,
            discNumber = 1,
            durationMs = 135000L,
            year = 1974,
            genre = "Hard Rock",
            coverUrl = "https://example.com/cover.jpg",
            albumId = "dz:382524",
            source = CatalogPlaylist.SOURCE_DEEZER,
        )
        val track = NebulaUrls.trackFor(catTrack)
        assertEquals("dz:382524", track.mbReleaseId)

        val catAlbumId = track.mbReleaseId ?: NebulaUrls.parseParams(track.uri)["albumId"]
        assertNotNull(catAlbumId)
        val catAlbum = CatalogAlbum(
            id = catAlbumId!!,
            title = track.album ?: "",
            artist = track.albumArtist ?: track.artist ?: "",
            year = track.year,
            coverUrl = track.artworkUri,
            genre = track.genre,
            trackCount = null,
            source = if (catAlbumId.startsWith("ap:")) CatalogPlaylist.SOURCE_APPLE else CatalogPlaylist.SOURCE_DEEZER,
        )

        assertEquals("dz:382524", catAlbum.id)
        assertEquals("Sheer Heart Attack", catAlbum.title)
        assertEquals("Queen", catAlbum.artist)
        assertEquals(1974, catAlbum.year)
        assertEquals(CatalogPlaylist.SOURCE_DEEZER, catAlbum.source)
    }
}
