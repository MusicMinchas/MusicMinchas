package com.kike.musicplayer.data.history

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.recommend.ListeningStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class HistoricalPlayImporterTest {

    private val fakeTracks = listOf(
        Track(
            id = "t1",
            source = Track.SOURCE_SAF,
            uri = "content://t1",
            fileName = "song1.mp3",
            mimeType = "audio/mp3",
            sizeBytes = 1000,
            title = "Dead End",
            artist = "In Flames",
            album = "Come Clarity",
            durationMs = 200_000,
        ),
        Track(
            id = "t2",
            source = Track.SOURCE_SAF,
            uri = "content://t2",
            fileName = "song2.mp3",
            mimeType = "audio/mp3",
            sizeBytes = 1000,
            title = "Someday",
            artist = "Nickelback",
            album = "The Long Road",
            durationMs = 210_000,
        ),
    )

    private class FakeHistoricalDatesRegistry : HistoricalDatesRegistry {
        val dates = mutableMapOf<String, Long>()
        val inApp = mutableMapOf<String, Long>()

        override val csvDates: StateFlow<Map<String, Long>> = MutableStateFlow(dates)
        override val inAppDates: StateFlow<Map<String, Long>> = MutableStateFlow(inApp)

        override fun recordInAppPlay(trackId: String, timestamp: Long) {
            inApp[trackId] = timestamp
        }

        override fun setCsvDates(dates: Map<String, Long>) {
            this.dates.putAll(dates)
        }

        override fun initializeInAppPlays(plays: Map<String, Long>) {
            inApp.putAll(plays)
        }

        override fun clear() {
            dates.clear()
        }
    }

    @Test
    fun testParseCsvLineWithQuotesAndCommas() {
        val line = "\"In Flames, The Band\",\"Song, With Comma\",\"Album Name\",123456"
        val parsed = HistoricalPlayImporter.parseCsvLine(line)
        assertEquals(4, parsed.size)
        assertEquals("In Flames, The Band", parsed[0])
        assertEquals("Song, With Comma", parsed[1])
        assertEquals("Album Name", parsed[2])
        assertEquals("123456", parsed[3])
    }

    @Test
    fun testImportAppleMusicCsvUpdatesHistoricalDates() = runBlocking {
        val csvData = """
            "Track Name","Last Played Date","Is User Initiated"
            "In Flames - Dead End",1467478959000,false
            "In Flames - Dead End",1467478959000,true
            "Nickelback - Someday",1636199596000,false
            "Unknown Artist - Unknown Song",1700000000000,false
        """.trimIndent()

        val store = FakeHistoricalDatesRegistry()
        val stream = ByteArrayInputStream(csvData.toByteArray(StandardCharsets.UTF_8))
        val stats = HistoricalPlayImporter.importCsv(stream, fakeTracks, store)

        assertEquals(2, stats.matchedTracks)
        assertEquals(2, stats.matchedPlays)
        assertEquals(1, stats.skippedDuplicates)
        assertEquals(1, stats.unmatchedRows)

        assertEquals(1467478959000L, store.dates["t1"])
        assertEquals(1636199596000L, store.dates["t2"])
    }

    @Test
    fun testImportItunesCsvUpdatesHistoricalDates() = runBlocking {
        val csvData = """
            "In Flames","Dead End","Come Clarity","2015-05-10 14:30:00","In Flames","200"
            "Nickelback","Someday","The Long Road","2018-10-12 09:15:00","Nickelback","210"
            "Nonexistent Artist","Song","Album","2020-01-01 12:00:00","","180"
        """.trimIndent()

        val store = FakeHistoricalDatesRegistry()
        val stream = ByteArrayInputStream(csvData.toByteArray(StandardCharsets.UTF_8))
        val stats = HistoricalPlayImporter.importCsv(stream, fakeTracks, store)

        assertEquals(2, stats.matchedTracks)
        assertEquals(2, stats.matchedPlays)
        assertEquals(1, stats.unmatchedRows)

        assertNotNull(store.dates["t1"])
        assertNotNull(store.dates["t2"])
    }

    @Test
    fun testPrecedenceRuleOverridingLastFmBulkImport() {
        val now = 1793300000000L // late October 2026
        val lastfmRecentPlay = now - 5 * 24 * 3600 * 1000L // 5 days ago (bulk import window)
        val itunesRealPlay = 1431268200000L // 2015-05-10

        val history = listOf(
            PlayHistory(trackId = "t1", playedAt = lastfmRecentPlay, listenedMs = 200_000, counted = true)
        )
        val historicalDates = mapOf("t1" to itunesRealPlay)
        val inAppPlays = emptyMap<String, Long>()

        val stats = ListeningStats(
            history = history,
            now = now,
            historicalDates = historicalDates,
            inAppPlayDates = inAppPlays,
        )

        // 1. Play count from Last.fm is kept
        assertEquals(1, stats.plays["t1"])

        // 2. But lastPlayed is overridden by the authentic iTunes 2015 date, NOT the 5-day-old Last.fm date!
        assertEquals(itunesRealPlay, stats.lastPlayed["t1"])
        assertTrue("daysSincePlayed should be > 1000 days", (stats.daysSincePlayed("t1") ?: 0.0) > 1000.0)
    }

    @Test
    fun testPrecedenceRuleInAppPlayAdvancesDate() {
        val now = 1793300000000L
        val itunesRealPlay = 1431268200000L // 2015
        val inAppGenuinePlay = now - 2 * 24 * 3600 * 1000L // 2 days ago in MusicMinchas app

        val history = listOf(
            PlayHistory(trackId = "t1", playedAt = inAppGenuinePlay, listenedMs = 200_000, counted = true)
        )
        val historicalDates = mapOf("t1" to itunesRealPlay)
        val inAppPlays = mapOf("t1" to inAppGenuinePlay)

        val stats = ListeningStats(
            history = history,
            now = now,
            historicalDates = historicalDates,
            inAppPlayDates = inAppPlays,
        )

        // The real in-app play occurred AFTER the 2015 iTunes date, so in-app play wins!
        assertEquals(inAppGenuinePlay, stats.lastPlayed["t1"])
        assertEquals(1, stats.plays["t1"])
    }
}
