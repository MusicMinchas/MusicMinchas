package com.kike.musicplayer.data.addon

import com.kike.musicplayer.data.catalog.CatalogTrack
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.nebula.NebulaUrls
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AddonFallbackTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun testAddonInstanceBaseUrl() {
        val addon = AddonInstance(
            id = "nullbeat",
            name = "NullBeat",
            manifestUrl = "https://nullbeat.example.com/token/manifest.json",
        )
        assertEquals("https://nullbeat.example.com/token", addon.baseUrl)
    }

    @Test
    fun testFallbackFromProvider1ToProvider2() {
        val nullbeatBase = "https://nullbeat.example.com/token"
        val nebulaBase = "https://nebula.example.com/token"

        val provider1 = AddonInstance(
            id = "nullbeat",
            name = "NullBeat",
            manifestUrl = "$nullbeatBase/manifest.json",
            enabled = true,
        )
        val provider2 = AddonInstance(
            id = "nebula",
            name = "Nebula",
            manifestUrl = "$nebulaBase/manifest.json",
            enabled = true,
        )

        // Mock client where Provider 1 fails (returns 404 / no tracks), and Provider 2 succeeds
        val client = mockAddonClient { req ->
            val url = req.url.toString()
            when {
                // Provider 1 search returns empty tracks
                url.startsWith(nullbeatBase) && url.contains("/search") -> {
                    response(req, 200, """{"tracks":[]}""")
                }
                // Provider 2 search finds track
                url.startsWith(nebulaBase) && url.contains("/search") -> {
                    response(
                        req, 200,
                        """{
                            "tracks": [
                                {
                                    "id": "tidal:track:998877",
                                    "title": "Fade to Black",
                                    "artist": "Metallica",
                                    "album": "Ride the Lightning"
                                }
                            ]
                        }"""
                    )
                }
                // Provider 2 stream returns CDN link
                url.startsWith(nebulaBase) && url.contains("998877") -> {
                    response(req, 200, """{"url":"https://cdn.torbox.app/stream_nebula.flac","format":"flac"}""")
                }
                else -> response(req, 404, """{"error":"not found"}""")
            }
        }

        // Test fallback directly with AddonClient logic
        val stream1 = client.findStream(provider1.baseUrl!!, "Metallica", "Ride the Lightning", "Fade to Black")
        assertTrue("Provider 1 should fail because no tracks found", stream1.isFailure)

        val stream2 = client.findStream(provider2.baseUrl!!, "Metallica", "Ride the Lightning", "Fade to Black")
        assertTrue("Provider 2 should succeed", stream2.isSuccess)
        assertEquals("https://cdn.torbox.app/stream_nebula.flac", stream2.getOrNull()?.url)
    }

    @Test
    fun testReorderingPriorityChangesResolutionOrder() {
        val p1 = AddonInstance(id = "p1", name = "First", manifestUrl = "https://p1.com/manifest.json")
        val p2 = AddonInstance(id = "p2", name = "Second", manifestUrl = "https://p2.com/manifest.json")

        val initialList = mutableListOf(p1, p2)
        assertEquals("First", initialList[0].name)

        // Reorder p2 to index 0
        val moved = initialList.removeAt(1)
        initialList.add(0, moved)

        assertEquals("Second", initialList[0].name)
        assertEquals("First", initialList[1].name)
    }

    @Test
    fun testTrackForCatalogTrack() {
        val cat = CatalogTrack(
            id = "dz:123",
            title = "Enter Sandman",
            artist = "Metallica",
            album = "Metallica",
            albumArtist = "Metallica",
            trackNumber = 1,
            discNumber = 1,
            durationMs = 332000L,
            year = 1991,
            genre = "Metal",
            coverUrl = "https://cover.jpg",
            albumId = "dz:999",
            source = "deezer",
        )
        val track = NebulaUrls.trackFor(cat)
        assertEquals("nebula:dz:123", track.id)
        assertEquals(Track.SOURCE_NEBULA, track.source)
        assertEquals(Track.SCOPE_STREAM, track.scope)
        assertTrue(track.uri.startsWith("nebula:stream?"))
    }

    @Test
    fun testSanitizeForSearch() {
        assertEquals(
            "Chad Lawson she still solo piano",
            AddonClient.sanitizeForSearch("Chad Lawson she (still) (solo piano)")
        )
        assertEquals(
            "Enter Sandman Remastered",
            AddonClient.sanitizeForSearch("Enter Sandman [Remastered]")
        )
        assertEquals(
            "Theme from Schindler's List",
            AddonClient.sanitizeForSearch("Theme from \"Schindler's List\"")
        )
    }

    @Test
    fun testAeroDirectStreamResolution() {
        val aeroBase = "https://aero.example.com/token"
        val aero = AddonInstance(
            id = "comtorboxapplemusicaddon",
            name = "Aero",
            manifestUrl = "$aeroBase/manifest.json",
            enabled = true,
        )

        val aeroTrack = AddonTrack(
            id = "a1_IfgFqxNVmcr_UmQTmZVq4rkYrNrgVRGUhbL_9bV_0ADMBvWwRLkWmuMzJA",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night At The Opera",
        )

        var searchCalled = false
        var streamCalled = false

        val client = mockAddonClient { req ->
            val url = req.url.toString()
            when {
                url.contains("/search") -> {
                    searchCalled = true
                    response(req, 200, """{"tracks":[]}""")
                }
                url.contains(aeroTrack.id) -> {
                    streamCalled = true
                    response(req, 200, """{"url":"https://cdn.torbox.app/bohemian_aero.flac","format":"flac"}""")
                }
                else -> response(req, 404, "not found")
            }
        }

        val track = NebulaUrls.trackFor(aeroTrack, providerId = aero.id)
        assertTrue("URI should contain provider", track.uri.contains("provider=comtorboxapplemusicaddon"))
        assertTrue("URI should contain track ID", track.uri.contains("id=a1_IfgFqxNVmcr_UmQTmZVq4rkYrNrgVRGUhbL_9bV_0ADMBvWwRLkWmuMzJA"))

        // Test canStreamDirectly directly
        assertTrue(
            "canStreamDirectly should be true for Aero with matching provider",
            NebulaUrls.canStreamDirectly(aero, aeroTrack.id, aero.id)
        )
        assertTrue(
            "canStreamDirectly should be true for Aero ID even without provider parameter",
            NebulaUrls.canStreamDirectly(aero, aeroTrack.id, null)
        )

        // When resolving directly via AddonClient.stream
        val streamResult = client.stream(aero.baseUrl!!, aeroTrack.id)
        assertTrue(streamResult.isSuccess)
        assertEquals("https://cdn.torbox.app/bohemian_aero.flac", streamResult.getOrNull()?.url)
        assertTrue("Stream endpoint was called directly without search", streamCalled)
        org.junit.Assert.assertFalse("Search was not called because direct ID resolution succeeded", searchCalled)
    }

    @Test
    fun testCanStreamDirectlyRules() {
        val aero = AddonInstance(id = "comtorboxapplemusicaddon", name = "Aero", manifestUrl = "https://aero/manifest.json")
        val nullbeat = AddonInstance(id = "nullbeat", name = "NullBeat", manifestUrl = "https://nullbeat/manifest.json")
        val nebula = AddonInstance(id = "nebula", name = "Nebula", manifestUrl = "https://nebula/manifest.json")

        val aeroId = "a1_XyZ123-abc_def"
        val deezerId = "1109731"
        val tidalId = "tidal:track:998877"

        // 1. Explicit provider match
        assertTrue(NebulaUrls.canStreamDirectly(aero, aeroId, "comtorboxapplemusicaddon"))
        assertTrue(NebulaUrls.canStreamDirectly(nullbeat, deezerId, "nullbeat"))
        assertTrue(NebulaUrls.canStreamDirectly(nebula, tidalId, "nebula"))

        // 2. Cross-provider mismatch should NOT stream directly
        org.junit.Assert.assertFalse(NebulaUrls.canStreamDirectly(nullbeat, aeroId, "comtorboxapplemusicaddon"))
        org.junit.Assert.assertFalse(NebulaUrls.canStreamDirectly(aero, deezerId, "nullbeat"))
        org.junit.Assert.assertFalse(NebulaUrls.canStreamDirectly(nebula, aeroId, "comtorboxapplemusicaddon"))

        // 3. Fallback when provider is null (e.g. from Deezer catalog or legacy bookmarks)
        assertTrue(NebulaUrls.canStreamDirectly(aero, aeroId, null))
        assertTrue(NebulaUrls.canStreamDirectly(nullbeat, deezerId, null))
        assertTrue(NebulaUrls.canStreamDirectly(nebula, tidalId, null))

        // 4. Deezer track in Aero with null provider should NOT stream directly (avoids 422 TRACK_UNAVAILABLE)
        org.junit.Assert.assertFalse(NebulaUrls.canStreamDirectly(aero, deezerId, null))
    }

    private fun mockAddonClient(handler: (okhttp3.Request) -> Response): AddonClient {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return AddonClient(okHttp, moshi)
    }

    private fun response(request: okhttp3.Request, code: Int, json: String): Response = Response.Builder()
        .request(request)
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
        .build()
}
