package com.kike.musicplayer.data.nebula

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NebulaClientTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun validateAcceptsValidMusicManifestWithStreamResource() {
        val json = """
            {
              "id": "org.stremio.nebula",
              "name": "Nebula (Tidal - Torrents)",
              "version": "1.0.0",
              "description": "Stream lossless music via Torbox",
              "icon": "https://example.com/icon.png",
              "resources": ["search", "stream", "catalog"],
              "types": ["music"],
              "contentType": "music"
            }
        """.trimIndent()

        val client = mockClient { req ->
            assertEquals("https://addon.nebula.org/manifest.json", req.url.toString())
            jsonResponse(json)
        }

        val result = client.validate("https://addon.nebula.org")
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals("Nebula (Tidal - Torrents)", manifest.name)
        assertEquals("https://example.com/icon.png", manifest.icon)
        assertTrue(manifest.resources?.contains("stream") == true)
    }

    @Test
    fun validateRejectsManifestWithoutStreamResource() {
        val json = """
            {
              "id": "org.stremio.movies",
              "name": "Movies Addon",
              "resources": ["catalog"]
            }
        """.trimIndent()

        val client = mockClient { _ -> jsonResponse(json) }
        val result = client.validate("https://addon.movies.org/manifest.json")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("missing 'stream' resource") == true)
    }

    @Test
    fun validateRejectsHttpError() {
        val client = mockClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("Not Found".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        val result = client.validate("https://addon.example.com")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 404") == true)
    }

    @Test
    fun searchParsesTracksAndAlbumsCorrectly() {
        val json = """
            {
              "tracks": [
                {
                  "id": "tidal:track:12345",
                  "title": "Bohemian Rhapsody",
                  "artist": "Queen",
                  "album": "A Night at the Opera",
                  "duration": 354,
                  "artworkURL": "https://example.com/queen.jpg"
                }
              ],
              "albums": [
                {
                  "id": "tidal:album:6789",
                  "title": "A Night at the Opera",
                  "artist": "Queen",
                  "artworkURL": "https://example.com/opera.jpg",
                  "trackCount": 12,
                  "year": 1975
                }
              ]
            }
        """.trimIndent()

        val client = mockClient { req ->
            assertTrue(req.url.toString().contains("/search?q=Queen+Bohemian+Rhapsody"))
            jsonResponse(json)
        }

        val result = client.search("https://nebula.example.com", "Queen Bohemian Rhapsody")
        assertTrue(result.isSuccess)
        val searchResp = result.getOrThrow()
        assertEquals(1, searchResp.tracks?.size)
        val track = searchResp.tracks!!.first()
        assertEquals("tidal:track:12345", track.id)
        assertEquals("Bohemian Rhapsody", track.title)
        assertEquals("Queen", track.artist)
        assertEquals("A Night at the Opera", track.album)
        assertEquals(354, track.duration)

        assertEquals(1, searchResp.albums?.size)
        assertEquals("A Night at the Opera", searchResp.albums!!.first().title)
    }

    @Test
    fun streamParsesDirectCdnUrl() {
        val json = """
            {
              "url": "https://stream.torbox.app/direct/flac/audio-track-123.flac",
              "format": "flac",
              "quality": "lossless"
            }
        """.trimIndent()

        val client = mockClient { req ->
            assertTrue(req.url.toString().endsWith("/stream/tidal%3Atrack%3A12345"))
            jsonResponse(json)
        }

        val result = client.stream("https://nebula.example.com", "tidal:track:12345")
        assertTrue(result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://stream.torbox.app/direct/flac/audio-track-123.flac", stream.url)
        assertEquals("flac", stream.format)
        assertEquals("lossless", stream.quality)
    }

    @Test
    fun findStreamMatchesAlbumCandidate() {
        val searchJson = """
            {
              "tracks": [
                {
                  "id": "tidal:track:1",
                  "title": "Get Lucky",
                  "artist": "Daft Punk",
                  "album": "Greatest Hits Live"
                },
                {
                  "id": "tidal:track:2",
                  "title": "Get Lucky",
                  "artist": "Daft Punk",
                  "album": "Random Access Memories"
                }
              ]
            }
        """.trimIndent()

        val streamJson = """
            {
              "url": "https://stream.torbox.app/direct/ram-get-lucky.flac",
              "format": "flac"
            }
        """.trimIndent()

        val client = mockClient { req ->
            val url = req.url.toString()
            if (url.contains("/search")) {
                jsonResponse(searchJson)
            } else if (url.contains("/stream/tidal%3Atrack%3A2")) {
                jsonResponse(streamJson)
            } else {
                error("Unexpected request: $url")
            }
        }

        val result = client.findStream("https://nebula.example.com", "Daft Punk", "Random Access Memories", "Get Lucky")
        assertTrue(result.isSuccess)
        assertEquals("https://stream.torbox.app/direct/ram-get-lucky.flac", result.getOrThrow().url)
    }

    @Test
    fun findStreamFallsBackToFirstTrackWhenNoAlbumMatches() {
        val searchJson = """
            {
              "tracks": [
                {
                  "id": "tidal:track:99",
                  "title": "Song",
                  "artist": "Artist",
                  "album": "Some Compilation"
                }
              ]
            }
        """.trimIndent()

        val streamJson = """
            {
              "url": "https://stream.torbox.app/direct/compilation-song.flac"
            }
        """.trimIndent()

        val client = mockClient { req ->
            val url = req.url.toString()
            if (url.contains("/search")) {
                jsonResponse(searchJson)
            } else if (url.contains("/stream/tidal%3Atrack%3A99")) {
                jsonResponse(streamJson)
            } else {
                error("Unexpected request: $url")
            }
        }

        val result = client.findStream("https://nebula.example.com", "Artist", "Nonexistent Album", "Song")
        assertTrue(result.isSuccess)
        assertEquals("https://stream.torbox.app/direct/compilation-song.flac", result.getOrThrow().url)
    }

    @Test
    fun findStreamFailsWhenNoTracksFound() {
        val searchJson = """{ "tracks": [] }"""
        val client = mockClient { _ -> jsonResponse(searchJson) }
        val result = client.findStream("https://nebula.example.com", "Artist", "Album", "Song")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No matching track found") == true)
    }

    private fun mockClient(handler: (okhttp3.Request) -> Response): NebulaClient {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return NebulaClient(okHttp, moshi)
    }

    private fun jsonResponse(body: String, code: Int = 200): Response {
        return Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .request(okhttp3.Request.Builder().url("https://nebula.example.com").build())
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
