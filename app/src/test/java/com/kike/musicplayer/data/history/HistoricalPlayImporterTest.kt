package com.kike.musicplayer.data.history

import com.kike.musicplayer.data.db.HistoryDao
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.recommend.ListeningStats
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    private class FakeHistoryDao(
        initialPlays: List<PlayHistory>,
        private val scrobbleMillis: List<Long> = emptyList(),
    ) : HistoryDao {
        val rows = initialPlays.toMutableList()
        private var nextId = (rows.maxOfOrNull { it.id } ?: 0L) + 1L

        override suspend fun insert(entry: PlayHistory): Long {
            val id = if (entry.id == 0L) nextId++ else entry.id
            rows.add(entry.copy(id = id))
            return id
        }

        override suspend fun insertAll(entries: List<PlayHistory>) {
            entries.forEach { insert(it) }
        }

        override suspend fun allPlayedAt(): List<Long> = rows.map { it.playedAt }

        override suspend fun allTrackTimestamps(): List<HistoryDao.TrackTimestamp> =
            rows.map { HistoryDao.TrackTimestamp(it.trackId, it.playedAt) }

        override suspend fun updateListening(id: Long, listenedMs: Long, counted: Boolean) {
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) rows[idx] = rows[idx].copy(listenedMs = listenedMs, counted = counted)
        }

        override suspend fun inAppLastPlayed(): List<HistoryDao.TrackTimestamp> = emptyList()

        override fun observeAll(): Flow<List<PlayHistory>> = MutableStateFlow(rows.toList())

        override suspend fun updateAll(entries: List<PlayHistory>) {
            for (updated in entries) {
                val idx = rows.indexOfFirst { it.id == updated.id }
                if (idx >= 0) rows[idx] = updated
            }
        }

        override suspend fun allAscending(): List<PlayHistory> = rows.sortedBy { it.playedAt }

        override suspend fun scrobbleUtsMillis(): List<Long> = scrobbleMillis
    }

    @Test
    fun testImportUnifiedAppleMusicCsvWithCol4ArtistAndDuplicates() = runBlocking {
        val unifiedTracks = listOf(
            Track(
                id = "nin-closer",
                source = Track.SOURCE_DRIVE,
                uri = "u1",
                fileName = "closer.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 1000,
                title = "Closer",
                artist = "Nine Inch Nails",
                album = "The Downward Spiral",
                durationMs = 373_000,
            ),
            Track(
                id = "halsey-closer",
                source = Track.SOURCE_SUBSONIC,
                scope = Track.SCOPE_SERVER,
                uri = "u2",
                fileName = "closer.flac",
                mimeType = "audio/flac",
                sizeBytes = 1000,
                title = "Closer",
                artist = "The Chainsmokers",
                album = "Collage EP",
                durationMs = 244_000,
            ),
            Track(
                id = "zara-never",
                source = Track.SOURCE_SUBSONIC,
                scope = Track.SCOPE_SERVER,
                uri = "u3",
                fileName = "never.flac",
                mimeType = "audio/flac",
                sizeBytes = 1000,
                title = "Never Forget You",
                artist = "Zara Larsson, MNEK",
                albumArtist = "Zara Larsson",
                album = "So Good",
                durationMs = 213_000,
            ),
        )
        // Includes UTF-8 BOM, corrupted Album Artist ("The Chainsmokers" for NIN), safe collab fallback ("Zara Larsson" for "Zara Larsson & MNEK"), and 1 exact duplicate
        val csvData = "\ufeff" + """
            Album Artist,Track Name,Album Name,Timestamp,Artist
            The Chainsmokers,Closer,The Downward Spiral,2022-05-10 14:30:00,Nine Inch Nails
            The Chainsmokers,Closer,The Downward Spiral,2022-05-10 14:30:00,Nine Inch Nails
            Zara Larsson,Never Forget You,Never Forget You - Single,2021-08-19 10:15:30,Zara Larsson & MNEK
        """.trimIndent()

        val store = FakeHistoricalDatesRegistry()
        val stats = HistoricalPlayImporter.importCsv(
            ByteArrayInputStream(csvData.toByteArray(StandardCharsets.UTF_8)),
            unifiedTracks,
            store,
        )

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals(3, stats.totalRows)
        assertEquals(2, stats.matchedTracks)
        assertEquals(2, stats.matchedPlays)
        assertEquals(1, stats.skippedDuplicates)
        assertEquals(fmt.parse("2022-05-10 14:30:00")!!.time, store.dates["nin-closer"])
        assertEquals(null, store.dates["halsey-closer"])
        assertEquals(fmt.parse("2021-08-19 10:15:30")!!.time, store.dates["zara-never"])
    }

    @Test
    fun testIsSafeArtistFallbackRejectsUnrelatedAlbumArtists() {
        assertTrue(HistoricalPlayImporter.isSafeArtistFallback("Zara Larsson & MNEK", "Zara Larsson"))
        assertTrue(HistoricalPlayImporter.isSafeArtistFallback("Fat Boy Slim", "Fatboy Slim"))
        assertFalse(HistoricalPlayImporter.isSafeArtistFallback("Nine Inch Nails", "The Chainsmokers"))
        assertFalse(HistoricalPlayImporter.isSafeArtistFallback("Led Zeppelin", "Billie Eilish"))
        assertFalse(HistoricalPlayImporter.isSafeArtistFallback("Green Day", "Piano Dreamers"))
    }

    @Test
    fun testMergeUpdatesUsesMaxOfPreservingNewerDates() {
        val existing = mapOf(
            "t_newer_itunes" to 1_750_000_000_000L, // 2025
            "t_older_itunes" to 1_500_000_000_000L, // 2017
        )
        val incoming = mapOf(
            "t_newer_itunes" to 1_580_000_000_000L, // 2020 (must NOT overwrite 2025!)
            "t_older_itunes" to 1_700_000_000_000L, // 2023 (must advance 2017 -> 2023!)
            "t_brand_new" to 1_650_000_000_000L,    // new entry
        )
        val updates = HistoricalDatesStore.mergeUpdates(existing, incoming)
        assertEquals(2, updates.size)
        assertFalse("t_newer_itunes" in updates)
        assertEquals(1_700_000_000_000L, updates["t_older_itunes"])
        assertEquals(1_650_000_000_000L, updates["t_brand_new"])
    }

    @Test
    fun testAlignPlayHistoryUncollapsesRowsAndProtectsInAppAndExistingDates() = runBlocking {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun utc(s: String) = fmt.parse(s)!!.time

        // Track 1 (KALEO style): 3 scrobbles collapsed onto prior HD anchor 2024-04-27 12:50:27
        val kaleoAnchor = utc("2024-04-27 12:50:27")
        val kaleoPlays = listOf(
            PlayHistory(id = 1, trackId = "kaleo", playedAt = kaleoAnchor - 180_000L * 2, listenedMs = 200_000, counted = true),
            PlayHistory(id = 2, trackId = "kaleo", playedAt = kaleoAnchor, listenedMs = 200_000, counted = true),
            PlayHistory(id = 3, trackId = "kaleo", playedAt = kaleoAnchor, listenedMs = 200_000, counted = true),
        )
        // Track 2 (SOAD Toxicity style): 2026-04-23 already has a .269ms play, 2 iTunes rows at 2023-11-13, PLUS a real Nov 6 in-app .212ms play!
        val soadInApp = utc("2026-11-06 10:38:57") + 212L
        val soadExistingAm = utc("2026-04-23 19:04:15") + 269L
        val soadItunes = utc("2023-11-13 08:33:57")
        val soadPlays = listOf(
            PlayHistory(id = 4, trackId = "soad", playedAt = soadItunes, listenedMs = 200_000, counted = true),
            PlayHistory(id = 5, trackId = "soad", playedAt = soadItunes, listenedMs = 200_000, counted = true),
            PlayHistory(id = 6, trackId = "soad", playedAt = soadExistingAm, listenedMs = 200_000, counted = true),
            PlayHistory(id = 7, trackId = "soad", playedAt = soadInApp, listenedMs = 219_641, counted = true),
        )
        // Track 3 (Ed Sheeran Nov 2026 bulk style): unaligned Nov 2026 scrobble
        val novBulk = utc("2026-11-06 22:14:00")
        val edPlays = listOf(
            PlayHistory(id = 8, trackId = "ed", playedAt = novBulk, listenedMs = 210_000, counted = true),
        )

        val dao = FakeHistoryDao(
            initialPlays = kaleoPlays + soadPlays + edPlays,
            scrobbleMillis = listOf(novBulk, utc("2026-11-08 10:00:00")),
        )

        HistoricalPlayImporter.alignPlayHistoryTimestamps(
            historyDao = dao,
            trackMatchedPlays = mapOf(
                "kaleo" to listOf(utc("2018-06-09 10:09:02"), utc("2023-09-20 15:21:44"), kaleoAnchor),
                "soad" to listOf(soadExistingAm - 269L), // Exact second matches 2026-04-23 already present in DB
                "ed" to listOf(utc("2024-10-07 15:20:02")),
            ),
            latestTrackDates = mapOf(
                "kaleo" to kaleoAnchor,
                "soad" to (soadExistingAm - 269L),
                "ed" to utc("2024-10-07 15:20:02"),
            ),
            inAppDates = mapOf("soad" to soadInApp),
            historicalCsvDates = mapOf("kaleo" to kaleoAnchor, "soad" to soadItunes),
        )

        val byTrack = dao.rows.groupBy { it.trackId }
        // 1. KALEO un-collapsed across its 3 real dates cleanly without losing count
        assertEquals(
            listOf(utc("2018-06-09 10:09:02"), utc("2023-09-20 15:21:44"), kaleoAnchor),
            byTrack["kaleo"]!!.map { it.playedAt }.sorted(),
        )
        // 2. SOAD kept existing 2026-04-23 .269ms, untouched 2023-11-13 rows, AND protected real Nov 6 in-app play
        assertEquals(4, byTrack["soad"]!!.size)
        assertEquals(soadInApp, byTrack["soad"]!!.last().playedAt)
        assertEquals(soadItunes, byTrack["soad"]!![0].playedAt)
        assertEquals(soadItunes, byTrack["soad"]!![1].playedAt)
        // 3. Ed Sheeran moved cleanly from Nov 2026 to 2024-10-07
        assertEquals(listOf(utc("2024-10-07 15:20:02")), byTrack["ed"]!!.map { it.playedAt })
    }

    private fun fakeTrackDao(allTracks: List<Track>): TrackDao {
        return Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    val scopes = args[0] as List<Int>
                    allTracks.filter { it.durationMs >= 0 && it.scope in scopes }
                }
                "allArtistSpellings" -> allTracks.flatMap { listOfNotNull(it.artist, it.albumArtist) }.distinct()
                "byArtistNamesChunk" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = (args[0] as List<String>).toSet()
                    allTracks.filter { it.artist in names || it.albumArtist in names }
                }
                "getByIdsChunk" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = (args[0] as List<String>).toSet()
                    allTracks.filter { it.id in ids }
                }
                else -> error("Unexpected TrackDao method in test: ${method.name}")
            }
        } as TrackDao
    }

    @Test
    fun testImportCsvFromDaoOnAlbumSubsonicBeatsOffAlbumDrive() = runBlocking {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun utc(s: String) = fmt.parse(s)!!.time

        val tracks = listOf(
            Track(
                id = "drive-queen-gh2",
                source = Track.SOURCE_DRIVE,
                scope = Track.SCOPE_DRIVE,
                uri = "d1",
                fileName = "magic.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 1000,
                title = "A Kind Of Magic",
                artist = "Queen",
                albumArtist = "Queen",
                album = "Greatest Hits II",
                durationMs = 264_000,
            ),
            Track(
                id = "sub-queen-plat",
                source = Track.SOURCE_SUBSONIC,
                scope = Track.SCOPE_SERVER,
                uri = "s1",
                fileName = "magic.flac",
                mimeType = "audio/flac",
                sizeBytes = 1000,
                title = "A Kind of Magic",
                artist = "Queen",
                albumArtist = "Queen",
                album = "The Platinum Collection",
                durationMs = 264_000,
            ),
            Track(
                id = "drive-epica-cappella",
                source = Track.SOURCE_DRIVE,
                scope = Track.SCOPE_DRIVE,
                uri = "d2",
                fileName = "rivers.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 1000,
                title = "Rivers (a cappella)",
                artist = "Epica",
                albumArtist = "Epica",
                album = "Omegacoustic - EP",
                durationMs = 269_000,
            ),
            Track(
                id = "sub-epica-capella",
                source = Track.SOURCE_SUBSONIC,
                scope = Track.SCOPE_SERVER,
                uri = "s2",
                fileName = "rivers.flac",
                mimeType = "audio/flac",
                sizeBytes = 1000,
                title = "Rivers - A Capella",
                artist = "Epica",
                albumArtist = "Epica",
                album = "Omegacoustic",
                durationMs = 274_000,
            ),
            Track(
                id = "drive-mega-youth",
                source = Track.SOURCE_DRIVE,
                scope = Track.SCOPE_DRIVE,
                uri = "d3",
                fileName = "tout.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 1000,
                title = "À tout le monde",
                artist = "Megadeth",
                album = "Youthanasia",
                durationMs = 267_000,
            ),
            Track(
                id = "sub-mega-anthology",
                source = Track.SOURCE_SUBSONIC,
                scope = Track.SCOPE_SERVER,
                uri = "s3",
                fileName = "tout_anth.flac",
                mimeType = "audio/flac",
                sizeBytes = 1000,
                title = "A Tout Le Monde (Remastered 2004)",
                artist = "Megadeth",
                albumArtist = "Megadeth",
                album = "Anthology: Set The World Afire",
                durationMs = 262_000,
            ),
        )

        val novQueen = utc("2026-11-06 15:09:00")
        val novEpica = utc("2026-11-09 12:28:00")
        val novMega = utc("2026-11-15 17:56:00")
        val historyDao = FakeHistoryDao(
            initialPlays = listOf(
                PlayHistory(id = 1, trackId = "sub-queen-plat", playedAt = novQueen, listenedMs = 264_000, counted = true),
                PlayHistory(id = 2, trackId = "sub-epica-capella", playedAt = novEpica, listenedMs = 274_000, counted = true),
                PlayHistory(id = 3, trackId = "sub-mega-anthology", playedAt = novMega, listenedMs = 262_000, counted = true),
            ),
            scrobbleMillis = listOf(novQueen, novEpica, novMega),
        )
        val csvData = """
            Album Artist,Track Name,Album Name,Timestamp,Artist
            Queen,A Kind of Magic,The Platinum Collection,2023-08-28 15:38:24,Queen
            Epica,Rivers - A Capella,Omegacoustic - EP,2021-03-06 16:06:25,Epica
            Megadeth,A Tout le Monde,Youthanasia,2024-02-10 09:14:39,Megadeth
            Megadeth,À Tout le Monde,Anthology: Set the World Afire,2022-06-03 18:37:37,Megadeth
        """.trimIndent()

        val store = FakeHistoricalDatesRegistry()
        val stats = HistoricalPlayImporter.importCsvFromDao(
            inputStream = ByteArrayInputStream(csvData.toByteArray(StandardCharsets.UTF_8)),
            trackDao = fakeTrackDao(tracks),
            historicalStore = store,
            historyDao = historyDao,
        )

        assertEquals(4, stats.matchedTracks)
        assertEquals(0, stats.unmatchedRows)
        assertEquals(utc("2023-08-28 15:38:24"), historyDao.rows.first { it.id == 1L }.playedAt)
        assertEquals(utc("2021-03-06 16:06:25"), historyDao.rows.first { it.id == 2L }.playedAt)
        assertEquals(utc("2022-06-03 18:37:37"), historyDao.rows.first { it.id == 3L }.playedAt)
    }

    @Test
    fun testItunesCsvUsesLocalTimezoneWhileUnifiedUsesUtc() = runBlocking {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+1"))
            val tracks = listOf(
                Track(
                    id = "soad-tox",
                    source = Track.SOURCE_DRIVE,
                    scope = Track.SCOPE_DRIVE,
                    uri = "d1",
                    fileName = "tox.m4a",
                    mimeType = "audio/mp4",
                    sizeBytes = 1000,
                    title = "Toxicity",
                    artist = "System Of A Down",
                    album = "Toxicity",
                    durationMs = 219_000,
                ),
            )
            val trackDao = fakeTrackDao(tracks)
            val storeItunes = FakeHistoricalDatesRegistry()
            HistoricalPlayImporter.importCsvFromDao(
                inputStream = ByteArrayInputStream("System Of A Down,Toxicity,Toxicity,2023-11-13 09:33:57".toByteArray(StandardCharsets.UTF_8)),
                trackDao = trackDao,
                historicalStore = storeItunes,
            )
            val storeUnified = FakeHistoricalDatesRegistry()
            HistoricalPlayImporter.importCsvFromDao(
                inputStream = ByteArrayInputStream(
                    "Album Artist,Track Name,Album Name,Timestamp,Artist\nSystem Of A Down,Toxicity,Toxicity,2026-04-23 19:04:15,System Of A Down".toByteArray(StandardCharsets.UTF_8),
                ),
                trackDao = trackDao,
                historicalStore = storeUnified,
            )

            val fmtUtc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            assertEquals(fmtUtc.parse("2023-11-13 08:33:57")!!.time, storeItunes.dates["soad-tox"])
            assertEquals(fmtUtc.parse("2026-04-23 19:04:15")!!.time, storeUnified.dates["soad-tox"])
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun testAlignPlayHistoryExcessFallbackSkipsAssignedTargetDate() = runBlocking {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun utc(s: String) = fmt.parse(s)!!.time

        val march13 = utc("2026-03-13 12:55:54")
        val nov1 = utc("2026-11-01 16:44:00")
        val nov2 = utc("2026-11-01 16:47:00")
        val nov3 = utc("2026-11-10 23:28:00")
        val dao = FakeHistoryDao(
            initialPlays = listOf(
                PlayHistory(id = 1, trackId = "blessed", playedAt = nov1, listenedMs = 200_000, counted = true),
                PlayHistory(id = 2, trackId = "blessed", playedAt = nov2, listenedMs = 200_000, counted = true),
                PlayHistory(id = 3, trackId = "blessed", playedAt = nov3, listenedMs = 200_000, counted = true),
            ),
            scrobbleMillis = listOf(nov1, nov2, nov3),
        )

        HistoricalPlayImporter.alignPlayHistoryTimestamps(
            historyDao = dao,
            trackMatchedPlays = mapOf("blessed" to listOf(march13)),
            latestTrackDates = mapOf("blessed" to march13),
            historicalCsvDates = mapOf("blessed" to march13),
        )

        val actual = dao.rows.map { it.playedAt }.sorted()
        assertEquals(listOf(march13 - 360_000L, march13 - 180_000L, march13), actual)
    }
}
