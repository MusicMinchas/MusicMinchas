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
import org.junit.Assert.assertFalse
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

    private fun sampleResponseFor(req: okhttp3.Request): String {
        val url = req.url.toString()
        if (url.contains("chart/0/playlists")) return sampleChartResponse
        val h = Math.abs(url.hashCode())
        return """
            {
              "data": [
                {
                  "id": ${h + 1},
                  "title": "Playlist ${h + 1}",
                  "nb_tracks": 80,
                  "picture_xl": "https://img/${h + 1}.jpg",
                  "user": {"name": "Editor"}
                },
                {
                  "id": ${h + 2},
                  "title": "Playlist ${h + 2}",
                  "nb_tracks": 50,
                  "picture_xl": "https://img/${h + 2}.jpg",
                  "user": {"name": "Editor"}
                }
              ]
            }
        """.trimIndent()
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
            jsonResponse(req, sampleResponseFor(req))
        }

        // 1. Initial fetch from network
        val content = client.fetchDeezerHomeContent(forceRefresh = false)
        assertNotNull(content)
        assertEquals(5, content!!.sections.size)
        assertEquals(5, networkCalls) // 4 chart endpoints + 1 mood search endpoint

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

        // Verify mood section is present
        val moodSection = content.sections[3]
        assertTrue(moodSection.id.startsWith("mood_"))
        assertEquals(2, moodSection.playlists.size)

        // Verify cache file was written
        val cacheFile = cacheDir.resolve("deezer_home_content.json")
        assertTrue(cacheFile.exists())
        assertTrue(cacheFile.length() > 0)

        // 2. Second fetch within 24h should read from cache without network calls
        val cachedContent = client.fetchDeezerHomeContent(forceRefresh = false)
        assertNotNull(cachedContent)
        assertEquals(5, networkCalls) // Still 5! No new network calls made
        assertEquals(content.sections.size, cachedContent!!.sections.size)
        assertEquals("Happy Hits", cachedContent.sections[0].playlists[0].title)

        // 3. Force refresh should bypass cache and re-fetch from network
        val refreshedContent = client.fetchDeezerHomeContent(forceRefresh = true)
        assertNotNull(refreshedContent)
        assertEquals(10, networkCalls) // 5 more calls made
    }

    @Test
    fun fetchDeezerHomeContentMatchesLibraryGenresDynamically() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_genres")
        val requestedUrls = mutableListOf<String>()

        val client = mockCatalogClient(cacheDir) { req ->
            requestedUrls.add(req.url.toString())
            jsonResponse(req, sampleResponseFor(req))
        }

        // Pass user's top genres: "Heavy Metal", "Electronic"
        val content = client.fetchDeezerHomeContent(
            forceRefresh = true,
            libraryGenres = listOf("Heavy Metal", "Electronic")
        )

        assertNotNull(content)
        assertEquals(5, content!!.sections.size)

        // Section 0 is always trending
        assertEquals("trending", content.sections[0].id)
        // Section 1 and 2 should match "metal" (genreId 464) and "electronic" (genreId 113)
        assertEquals("metal", content.sections[1].id)
        assertEquals("electronic", content.sections[2].id)
        // Section 3 is mood of the day
        assertTrue(content.sections[3].id.startsWith("mood_"))

        // All requests must request limit=30
        assertTrue(requestedUrls.all { it.contains("limit=30") })
        assertTrue(requestedUrls.any { it.contains("chart/0/playlists") })
        assertTrue(requestedUrls.any { it.contains("chart/464/playlists") })
        assertTrue(requestedUrls.any { it.contains("chart/113/playlists") })
        assertTrue(requestedUrls.any { it.contains("search/playlist") })
    }

    @Test
    fun fetchDeezerHomeContentScoresCuratedGenresByCumulativeSongCount() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_scoring")
        val requestedUrls = mutableListOf<String>()

        val client = mockCatalogClient(cacheDir) { req ->
            requestedUrls.add(req.url.toString())
            jsonResponse(req, sampleResponseFor(req))
        }

        // Library has subgenres: Metal subgenres total 900, Rock total 300, Pop 100
        val counts = mapOf(
            "Thrash Metal" to 500,
            "Symphonic Metal" to 400,
            "Hard Rock" to 300,
            "Pop" to 100,
        )

        val content = client.fetchDeezerHomeContent(
            forceRefresh = true,
            genreCounts = counts,
        )

        assertNotNull(content)
        // Top 1 must be metal (score 900), Top 2 must be rock (score 300)
        assertEquals("trending", content!!.sections[0].id)
        assertEquals("metal", content.sections[1].id)
        assertEquals("rock", content.sections[2].id)
    }

    @Test
    fun fetchDeezerHomeContentScoresCuratedGenresByPlayHistoryCounts() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_play_history")
        val client = mockCatalogClient(cacheDir) { req ->
            jsonResponse(req, sampleResponseFor(req))
        }

        // Play history dominated by metal and rock plays
        val playHistoryCounts = mapOf(
            "Symphonic Metal" to 3309,
            "Metal" to 3163,
            "Rock" to 2133,
            "Gothic Rock" to 2008,
            "Pop" to 1507,
            "Gothic Metal" to 1486,
            "Nu-Metal" to 1140,
        )

        val content = client.fetchDeezerHomeContent(
            forceRefresh = true,
            genreCounts = playHistoryCounts,
        )

        assertNotNull(content)
        assertEquals("trending", content!!.sections[0].id)
        assertEquals("metal", content.sections[1].id)
        assertEquals("rock", content.sections[2].id)
    }

    @Test
    fun fetchDeezerHomeContentDeduplicatesPlaylistsAcrossSections() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_dedupe")

        val client = mockCatalogClient(cacheDir) { req ->
            val url = req.url.toString()
            if (url.contains("chart/0")) {
                val json = """
                    {
                        "data": [
                            {"id": 101, "title": "Happy Hits", "nb_tracks": 50, "picture_medium": "https://img/1.jpg"},
                            {"id": 102, "title": "Trending Anthem", "nb_tracks": 40, "picture_medium": "https://img/2.jpg"}
                        ]
                    }
                """.trimIndent()
                jsonResponse(req, json)
            } else if (url.contains("chart/464")) {
                // Returns "Happy Hits" again (duplicate) + "Heavy Metal Hits" (unique)
                val json = """
                    {
                        "data": [
                            {"id": 101, "title": "Happy Hits", "nb_tracks": 50, "picture_medium": "https://img/1.jpg"},
                            {"id": 103, "title": "Heavy Metal Hits", "nb_tracks": 35, "picture_medium": "https://img/3.jpg"}
                        ]
                    }
                """.trimIndent()
                jsonResponse(req, json)
            } else {
                jsonResponse(req, sampleResponseFor(req))
            }
        }

        val content = client.fetchDeezerHomeContent(
            forceRefresh = true,
            genreCounts = mapOf("Metal" to 100),
        )

        assertNotNull(content)
        val s0 = content!!.sections[0] // Trending
        val s1 = content.sections[1]   // Metal

        // Trending has Happy Hits (id dz:101)
        assertTrue(s0.playlists.any { it.id == "dz:101" })
        // Metal section must have dropped the duplicate Happy Hits and kept only Heavy Metal Hits
        assertFalse(s1.playlists.any { it.id == "dz:101" })
        assertTrue(s1.playlists.any { it.id == "dz:103" })
    }

    @Test
    fun fetchDeezerHomeContentFallsBackToCacheOnNetworkError() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        var shouldFail = false

        val client = mockCatalogClient(cacheDir) { req ->
            if (shouldFail) {
                throw IOException("Simulated network outage")
            } else {
                jsonResponse(req, sampleResponseFor(req))
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
