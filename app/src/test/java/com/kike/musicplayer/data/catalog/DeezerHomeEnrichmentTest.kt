package com.kike.musicplayer.data.catalog

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DeezerHomeEnrichmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun mockCatalogClient(cacheDir: java.io.File, handler: (okhttp3.Request) -> Response): CatalogClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
        return CatalogClient(okHttpClient, moshi, cacheDir)
    }

    private fun jsonResponse(req: okhttp3.Request, body: String, code: Int = 200): Response {
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private val sampleChartResponse = """
        {
          "data": [
            {
              "id": 1479458365,
              "title": "Happy Hits",
              "nb_tracks": 80,
              "picture_xl": "https://e-cdns-images.dzcdn.net/images/playlist/1479458365/1000x1000.jpg",
              "user": {
                "id": 1,
                "name": "Deezer Hits Editor"
              }
            },
            {
              "id": 4590807924,
              "title": "Calm Piano",
              "nb_tracks": 200,
              "picture_xl": "https://e-cdns-images.dzcdn.net/images/playlist/4590807924/1000x1000.jpg",
              "user": {
                "id": 2,
                "name": "Deezer Moods"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun fetchDeezerHomeContentParsesPlaylistsAndCachesToDisk() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        var networkCalls = 0

        val client = mockCatalogClient(cacheDir) { req ->
            networkCalls++
            jsonResponse(req, sampleChartResponse)
        }

        // 1. Initial fetch from network
        val content = client.fetchDeezerHomeContent(forceRefresh = false)
        assertNotNull(content)
        assertEquals(4, content!!.sections.size)
        assertEquals(4, networkCalls) // 4 chart endpoints

        val trending = content.sections.first { it.id == "trending" }
        assertEquals("Trending on Deezer", trending.title)
        assertEquals("Most popular playlists worldwide", trending.subtitle)
        assertEquals(2, trending.playlists.size)

        val firstPlaylist = trending.playlists[0]
        assertEquals("dz:1479458365", firstPlaylist.id)
        assertEquals("Happy Hits", firstPlaylist.title)
        assertEquals("Deezer Hits Editor", firstPlaylist.creator)
        assertEquals(80, firstPlaylist.trackCount)
        assertEquals("https://e-cdns-images.dzcdn.net/images/playlist/1479458365/1000x1000.jpg", firstPlaylist.coverUrl)
        assertEquals(CatalogPlaylist.SOURCE_DEEZER, firstPlaylist.source)

        // Verify cache file was written
        val cacheFile = cacheDir.resolve("deezer_home_content.json")
        assertTrue(cacheFile.exists())
        assertTrue(cacheFile.length() > 0)

        // 2. Second fetch within 24h should read from cache without network calls
        val cachedContent = client.fetchDeezerHomeContent(forceRefresh = false)
        assertNotNull(cachedContent)
        assertEquals(4, networkCalls) // Still 4! No new network calls made
        assertEquals(content.sections.size, cachedContent!!.sections.size)
        assertEquals("Happy Hits", cachedContent.sections[0].playlists[0].title)

        // 3. Force refresh should bypass cache and re-fetch from network
        val refreshedContent = client.fetchDeezerHomeContent(forceRefresh = true)
        assertNotNull(refreshedContent)
        assertEquals(8, networkCalls) // 4 more calls made
    }

    @Test
    fun fetchDeezerHomeContentFallsBackToCacheOnNetworkError() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        var shouldFail = false

        val client = mockCatalogClient(cacheDir) { req ->
            if (shouldFail) {
                throw IOException("Simulated network outage")
            } else {
                jsonResponse(req, sampleChartResponse)
            }
        }

        // 1. Successful fetch populates cache
        val initialContent = client.fetchDeezerHomeContent()
        assertNotNull(initialContent)

        // 2. Network fails, but forceRefresh is requested -> falls back to existing cache
        shouldFail = true
        val fallbackContent = client.fetchDeezerHomeContent(forceRefresh = true)
        assertNotNull(fallbackContent)
        assertEquals(initialContent!!.sections.size, fallbackContent!!.sections.size)
        assertEquals("Happy Hits", fallbackContent.sections[0].playlists[0].title)
    }

    @Test
    fun moshiSerializationRoundtrip() {
        val sections = listOf(
            DeezerHomeSection(
                id = "trending",
                title = "Trending on Deezer",
                subtitle = "Top playlists",
                playlists = listOf(
                    CatalogPlaylist(
                        id = "dz:12345",
                        title = "Top Rock",
                        creator = "Deezer",
                        trackCount = 50,
                        coverUrl = "https://example.com/cover.jpg",
                    )
                )
            )
        )
        val original = DeezerHomeContent(sections = sections, fetchedAt = 123456789L)

        val adapter = moshi.adapter(DeezerHomeContent::class.java)
        val json = adapter.toJson(original)
        val deserialized = adapter.fromJson(json)

        assertNotNull(deserialized)
        assertEquals(original.fetchedAt, deserialized!!.fetchedAt)
        assertEquals(1, deserialized.sections.size)
        assertEquals("trending", deserialized.sections[0].id)
        assertEquals("Trending on Deezer", deserialized.sections[0].title)
        assertEquals("Top playlists", deserialized.sections[0].subtitle)
        assertEquals(1, deserialized.sections[0].playlists.size)
        assertEquals("dz:12345", deserialized.sections[0].playlists[0].id)
        assertEquals("Top Rock", deserialized.sections[0].playlists[0].title)
    }
}
