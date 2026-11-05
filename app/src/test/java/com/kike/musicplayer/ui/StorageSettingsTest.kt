package com.kike.musicplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageSettingsTest {

    @Test
    fun storageStatsDefaultValues() {
        val stats = StorageStats(
            appBytes = 65_000_000L,
            databaseBytes = 390_000_000L,
            databaseTrackCount = 304_382,
            databaseAlbumCount = 50_381,
            databaseArtistCount = 12_962,
            databaseScrobbleCount = 27_149,
            artworkBytes = 48_000_000L,
            artworkCount = 450,
            imageCacheBytes = 250_000_000L,
            imageCacheCount = 1_850,
            codeCacheBytes = 15_000_000L,
            loading = false,
        )

        assertEquals(65_000_000L, stats.appBytes)
        assertEquals(390_000_000L, stats.databaseBytes)
        assertEquals(304_382, stats.databaseTrackCount)
        assertEquals(50_381, stats.databaseAlbumCount)
        assertEquals(12_962, stats.databaseArtistCount)
        assertEquals(27_149, stats.databaseScrobbleCount)
        assertEquals(48_000_000L, stats.artworkBytes)
        assertEquals(450, stats.artworkCount)
        assertEquals(250_000_000L, stats.imageCacheBytes)
        assertEquals(1_850, stats.imageCacheCount)
        assertEquals(15_000_000L, stats.codeCacheBytes)
        assertFalse(stats.loading)
    }
}
