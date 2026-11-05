package com.kike.musicplayer.ui

import com.kike.musicplayer.data.catalog.CachedCatalogPlaylistData
import com.kike.musicplayer.data.catalog.CatalogTrack
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class CatalogPlaylistCacheTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun serializeAndDeserializeCachedCatalogPlaylist() {
        val adapter = moshi.adapter(CachedCatalogPlaylistData::class.java)

        val tracks = listOf(
            CatalogTrack(
                id = "dz:101",
                title = "Song One",
                artist = "Artist A",
                album = "Album X",
                albumArtist = "Artist A",
                trackNumber = 1,
                discNumber = 1,
                durationMs = 210000L,
                year = 2022,
                genre = "Rock",
                coverUrl = "https://e-cdns-images.dzcdn.net/images/cover/123/250x250.jpg",
                albumId = "dz:alb1",
                source = "deezer",
            ),
            CatalogTrack(
                id = "dz:102",
                title = "Song Two",
                artist = "Artist B",
                album = "Album Y",
                albumArtist = null,
                trackNumber = 2,
                discNumber = 1,
                durationMs = 185000L,
                year = 2023,
                genre = "Pop",
                coverUrl = "https://e-cdns-images.dzcdn.net/images/cover/456/250x250.jpg",
                albumId = "dz:alb2",
                source = "deezer",
            )
        )

        val matches = mapOf(
            "dz:101" to "local_5001",
            "dz:102" to "subsonic:5002",
        )

        val now = 1700000000000L
        val original = CachedCatalogPlaylistData(tracks, matches, fetchedAt = now)

        val json = adapter.toJson(original)
        assertNotNull(json)
        assertTrue(json.contains("dz:101"))
        assertTrue(json.contains("Song One"))
        assertTrue(json.contains("local_5001"))

        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.tracks.size)
        assertEquals("Song One", parsed.tracks[0].title)
        assertEquals("Song Two", parsed.tracks[1].title)
        assertEquals(2, parsed.matches.size)
        assertEquals("local_5001", parsed.matches["dz:101"])
        assertEquals("subsonic:5002", parsed.matches["dz:102"])
        assertEquals(now, parsed.fetchedAt)
    }

    @Test
    fun sevenDayTtlValidation() {
        val ttlMs = 7 * 24 * 3600 * 1000L
        val now = System.currentTimeMillis()

        // 3 days old: fresh
        val freshTimestamp = now - (3 * 24 * 3600 * 1000L)
        assertTrue((now - freshTimestamp) < ttlMs)

        // 6 days 23 hours old: fresh
        val nearExpiryTimestamp = now - (6 * 24 * 3600 * 1000L + 23 * 3600 * 1000L)
        assertTrue((now - nearExpiryTimestamp) < ttlMs)

        // 7 days and 1 second old: expired
        val expiredTimestamp = now - (ttlMs + 1000L)
        assertFalse((now - expiredTimestamp) < ttlMs)

        // 14 days old: expired
        val staleTimestamp = now - (14 * 24 * 3600 * 1000L)
        assertFalse((now - staleTimestamp) < ttlMs)
    }
}
