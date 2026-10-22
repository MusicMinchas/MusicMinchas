package com.kike.musicplayer.data.nebula

import com.kike.musicplayer.data.catalog.CatalogTrack
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.isRemote
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NebulaUrlsTest {

    @Test
    fun uriForAndParseParamsRoundtrip() {
        val uri = NebulaUrls.uriFor(
            artist = "AC/DC & The Who",
            album = "Back In Black (Deluxe)",
            title = "Hells Bells [Remastered]",
            trackId = "tidal:track:998877",
        )
        assertTrue(uri.startsWith("nebula:stream?"))

        val params = NebulaUrls.parseParams(uri)
        assertEquals("AC/DC & The Who", params["artist"])
        assertEquals("Back In Black (Deluxe)", params["album"])
        assertEquals("Hells Bells [Remastered]", params["title"])
        assertEquals("tidal:track:998877", params["id"])
    }

    @Test
    fun uriForWithoutOptionalFields() {
        val uri = NebulaUrls.uriFor(
            artist = "Queen",
            album = null,
            title = "Radio Ga Ga",
            trackId = null,
        )
        val params = NebulaUrls.parseParams(uri)
        assertEquals("Queen", params["artist"])
        assertEquals("Radio Ga Ga", params["title"])
        assertNull(params["album"])
        assertNull(params["id"])
    }

    @Test
    fun trackForCreatesValidTransientTrack() {
        val catalogTrack = CatalogTrack(
            id = "tidal:track:5544",
            title = "Starman",
            artist = "David Bowie",
            album = "The Rise and Fall of Ziggy Stardust",
            albumArtist = "David Bowie",
            trackNumber = 4,
            discNumber = 1,
            durationMs = 256000L,
            year = 1972,
            genre = "Glam Rock",
            coverUrl = "https://example.com/starman.jpg",
            albumId = "tidal:album:999",
            source = "tidal",
        )

        val track = NebulaUrls.trackFor(catalogTrack)
        assertEquals("nebula:tidal:track:5544", track.id)
        assertEquals(Track.SOURCE_NEBULA, track.source)
        assertEquals("audio/flac", track.mimeType)
        assertEquals("Starman.flac", track.fileName)
        assertEquals("Starman", track.title)
        assertEquals("David Bowie", track.artist)
        assertEquals("The Rise and Fall of Ziggy Stardust", track.album)
        assertEquals(4, track.trackNumber)
        assertEquals(1972, track.year)
        assertEquals(256000L, track.durationMs)
        assertEquals("https://example.com/starman.jpg", track.artworkUri)
        assertEquals("tidal:album:999", track.mbReleaseId)
        assertEquals("tidal:album:999", NebulaUrls.parseParams(track.uri)["albumId"])
        assertTrue(track.isRemote)
    }

    @Test
    fun trackIsRemoteForNebula() {
        val track = Track(
            id = "nebula:123",
            source = Track.SOURCE_NEBULA,
            uri = "nebula:stream?artist=A&title=B",
            fileName = "song.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = "B",
            artist = "A",
            album = "C",
            albumArtist = null,
            trackNumber = null,
            discNumber = null,
            year = null,
            genre = null,
            durationMs = 1000L,
            artworkUri = null,
        )
        assertTrue(track.isRemote)
    }

    @Test
    fun resolveUsesCache() {
        val requestCount = AtomicInteger(0)
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .request(chain.request())
                    .body("""{"url":"https://stream.torbox.app/cached.flac"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = NebulaClient(okHttp, moshi)
        val nebulaUrls = NebulaUrls(
            clientProvider = { client },
            baseUrlProvider = { "https://nebula.example.com" },
        )
        val uri = "nebula:stream?artist=Test&title=CachedSong&id=tidal:track:11"

        // First resolve calls client
        val url1 = nebulaUrls.resolve(uri)
        assertEquals("https://stream.torbox.app/cached.flac", url1)
        assertEquals(1, requestCount.get())

        // Second resolve hits cache
        val url2 = nebulaUrls.resolve(uri)
        assertEquals("https://stream.torbox.app/cached.flac", url2)
        assertEquals(1, requestCount.get()) // No new HTTP request
    }

    @Test
    fun resolveReturnsNullForNonNebulaUri() {
        val client = NebulaClient(OkHttpClient(), Moshi.Builder().add(KotlinJsonAdapterFactory()).build())
        val nebulaUrls = NebulaUrls(
            clientProvider = { client },
            baseUrlProvider = { "https://nebula.example.com" },
        )
        assertNull(nebulaUrls.resolve("https://example.com/stream.mp3"))
        assertNull(nebulaUrls.resolve("content://media/external/audio/media/1"))
    }
}
