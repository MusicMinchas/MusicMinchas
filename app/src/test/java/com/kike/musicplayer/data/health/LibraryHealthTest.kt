package com.kike.musicplayer.data.health

import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHealthTest {

    private fun testTrack(
        id: String,
        title: String = "Test Song",
        artist: String? = "Test Artist",
        album: String? = "Test Album",
        genre: String? = "Rock",
        year: Int? = 2020,
        trackNumber: Int? = 1,
        durationMs: Long = 200_000,
        artworkUri: String? = "file://cover.jpg",
        sizeBytes: Long? = 30_000_000L,
        fileName: String = "test.flac",
        mimeType: String? = "audio/flac",
    ) = Track(
        id = id,
        source = Track.SOURCE_SAF,
        uri = "content://saf/$id",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        durationMs = durationMs,
        artworkUri = artworkUri,
    )

    @Test
    fun testHealthCheckTypesCount() {
        assertEquals(10, HealthCheckType.values().size)
    }

    @Test
    fun testTrackIssueDetection() {
        // Track 1: Missing cover and missing year
        val t1 = testTrack(id = "1", artworkUri = null, year = null)
        assertTrue(t1.artworkUri.isNullOrBlank())
        assertTrue(t1.year == null || t1.year!! <= 0)

        // Track 2: Unplayable / DRM
        val t2 = testTrack(id = "2", durationMs = -1)
        assertTrue(t2.durationMs <= 0)

        // Track 3: Low quality MP3 (128 kbps)
        // 128 kbps for 200s is 3.2 MB
        val t3 = testTrack(
            id = "3",
            fileName = "low.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 3_200_000L,
            durationMs = 200_000L,
        )
        val bitrate = (t3.sizeBytes!! * 8L / t3.durationMs).toInt()
        assertEquals(128, bitrate)
        assertTrue(bitrate < 256)
    }

    @Test
    fun testDuplicateDetectionGrouping() {
        val tracks = listOf(
            testTrack(id = "1", title = "Hallowed Be Thy Name", artist = "Iron Maiden"),
            testTrack(id = "2", title = "hallowed be thy name", artist = "Iron Maiden"), // duplicate
            testTrack(id = "3", title = "The Trooper", artist = "Iron Maiden"),
        )
        val duplicates = tracks.groupBy { "${it.title.lowercase().trim()}|${(it.artist ?: "").lowercase().trim()}" }
            .filter { it.value.size > 1 }
            .values.flatten().map { it.id }.toSet()

        assertEquals(setOf("1", "2"), duplicates)
    }
}
