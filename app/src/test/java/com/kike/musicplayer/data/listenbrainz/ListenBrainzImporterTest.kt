package com.kike.musicplayer.data.listenbrainz

import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.FavouriteDao
import com.kike.musicplayer.data.db.HistoryDao
import com.kike.musicplayer.data.db.LastFmScrobble
import com.kike.musicplayer.data.db.LastFmScrobbleDao
import com.kike.musicplayer.data.db.LastFmScrobbleKey
import com.kike.musicplayer.data.db.ListenBrainzListen
import com.kike.musicplayer.data.db.ListenBrainzListenDao
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.ScrobbleCounts
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.UtsTrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ListenBrainzImporterTest {

    private class FakeListenDao : ListenBrainzListenDao {
        val rows = LinkedHashMap<Triple<String, Long, String>, ListenBrainzListen>()

        override suspend fun insertAll(listens: List<ListenBrainzListen>): List<Long> =
            listens.map { l ->
                val key = Triple(l.user, l.uts, l.msid)
                if (key in rows) -1L else {
                    rows[key] = l
                    rows.size.toLong()
                }
            }

        override suspend fun latest(user: String): Long? =
            rows.values.filter { it.user == user }.maxOfOrNull { it.uts }

        override suspend fun unmatched(): List<ListenBrainzListen> =
            rows.values.filter { it.trackId == null }

        override suspend fun setTrackIds(listens: List<ListenBrainzListen>) {
            for (l in listens) rows[Triple(l.user, l.uts, l.msid)] = l
        }

        override fun observeCounts(user: String): Flow<ScrobbleCounts> {
            val mine = rows.values.filter { it.user == user }
            return flowOf(ScrobbleCounts(mine.size, mine.count { it.trackId == null }))
        }

        override suspend fun countAll(): Int = rows.size
        override suspend fun countNotInLastFm(): Int = rows.size
    }

    private class FakeLastFmDao(
        private val scrobbles: MutableList<LastFmScrobble> = mutableListOf(),
    ) : LastFmScrobbleDao {
        override suspend fun insertAll(scrobbles: List<LastFmScrobble>) = emptyList<Long>()
        override suspend fun latest(user: String) = scrobbles.maxOfOrNull { it.uts }
        override suspend fun unmatched() = scrobbles.filter { it.trackId == null }
        override suspend fun setTrackIds(scrobbles: List<LastFmScrobble>) {}
        override fun observeCounts(user: String) = flowOf(ScrobbleCounts(scrobbles.size, scrobbles.count { it.trackId == null }))
        override suspend fun countAll() = scrobbles.size
        override suspend fun allUts(): List<Long> = scrobbles.map { it.uts }
        override suspend fun matchedPairs(): List<UtsTrackId> =
            scrobbles.mapNotNull { s -> s.trackId?.let { UtsTrackId(s.uts, it) } }
        override suspend fun allKeys(): List<LastFmScrobbleKey> =
            scrobbles.map { LastFmScrobbleKey(it.uts, it.artist, it.track, it.trackId) }
    }

    private class FakeHistoryDao(initial: List<PlayHistory> = emptyList()) : HistoryDao {
        val plays = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

        override suspend fun insert(entry: PlayHistory): Long {
            val id = nextId++
            plays += entry.copy(id = id)
            return id
        }

        override suspend fun insertAll(entries: List<PlayHistory>) {
            for (e in entries) insert(e)
        }

        override suspend fun allPlayedAt(): List<Long> = plays.map { it.playedAt }
        override suspend fun allTrackTimestamps(): List<HistoryDao.TrackTimestamp> =
            plays.map { HistoryDao.TrackTimestamp(it.trackId, it.playedAt) }
        override suspend fun updateListening(id: Long, listenedMs: Long, counted: Boolean) {}
        override suspend fun inAppLastPlayed() = emptyList<HistoryDao.TrackTimestamp>()
        override fun observeAll() = flowOf(plays.toList())
        override suspend fun updateAll(entries: List<PlayHistory>) {}
        override suspend fun allAscending() = plays.sortedBy { it.playedAt }
        override suspend fun scrobbleUtsMillis() = emptyList<Long>()
    }

    private class FakeFavouriteDao(initial: List<Favourite> = emptyList()) : FavouriteDao {
        val map = LinkedHashMap<String, Favourite>().apply { initial.forEach { put(it.trackId, it) } }

        override fun observeAll(): Flow<List<Favourite>> = flowOf(map.values.toList())
        override suspend fun insert(favourite: Favourite) { map.putIfAbsent(favourite.trackId, favourite) }
        override suspend fun insertAll(favourites: List<Favourite>): List<Long> =
            favourites.map { f -> if (f.trackId in map) -1L else { map[f.trackId] = f; 1L } }
        override suspend fun delete(trackId: String) { map.remove(trackId) }
        override suspend fun allTrackIds(): List<String> = map.keys.toList()
        override suspend fun serverTrackIds(): List<String> = map.keys.filter { it.startsWith("subsonic:") }
        override fun observeFavouriteMetas() = flowOf(emptyList<com.kike.musicplayer.data.db.FavouriteSongMeta>())
    }

    private fun fakeTrackDao(library: List<Track>): TrackDao =
        Proxy.newProxyInstance(TrackDao::class.java.classLoader, arrayOf(TrackDao::class.java)) { _, method, args ->
            when (method.name) {
                "allArtistSpellings" -> library.flatMap { listOfNotNull(it.artist, it.albumArtist) }.distinct()
                "byArtistNamesChunk" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = (args[0] as List<String>).toSet()
                    library.filter { it.artist in names || it.albumArtist in names }
                }
                "byRecordingIdsChunk" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = (args[0] as List<String>).toSet()
                    library.filter { it.mbRecordingId in ids }
                }
                "getByIdsChunk" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = (args[0] as List<String>).toSet()
                    library.filter { it.id in ids }
                }
                else -> error("Unexpected TrackDao call: ${method.name}")
            }
        } as TrackDao

    private fun track(
        id: String,
        artist: String,
        title: String,
        album: String? = null,
        mbid: String? = null,
        source: String = Track.SOURCE_DRIVE,
    ) = Track(
        id = id,
        source = source,
        uri = id,
        fileName = "$title.m4a",
        mimeType = "audio/mp4",
        sizeBytes = 1000L,
        title = title,
        artist = artist,
        album = album,
        durationMs = 240_000L,
        mbRecordingId = mbid,
        scope = if (source == Track.SOURCE_SUBSONIC) Track.SCOPE_SERVER else Track.SCOPE_DRIVE,
    )

    private class FakeApi(
        var totalCount: Int = 0,
        var pagesByMaxTs: (Long?) -> List<RemoteListen> = { emptyList() },
        var lovedPages: List<FeedbackPage> = emptyList(),
        var recordingMeta: Map<String, MetadataLookupResult> = emptyMap(),
    ) : ListenBrainzApiClient {
        override suspend fun validateToken(token: String) = TokenValidation(true, "alice")
        override suspend fun submitListens(token: String, listenType: String, submissions: List<ListenSubmission>) {}
        override suspend fun getListenCount(user: String, token: String?) = totalCount
        override suspend fun getListens(user: String, token: String?, maxTs: Long?, count: Int) =
            ListensPage(pagesByMaxTs(maxTs))
        override suspend fun lookupMetadata(artist: String, recording: String, release: String?, token: String?) = null
        override suspend fun getRecordingMetadata(mbids: List<String>, token: String?) =
            recordingMeta.filterKeys { it in mbids }
        override suspend fun submitFeedback(token: String, recordingMbid: String?, recordingMsid: String?, score: Int) {}
        override suspend fun getFeedback(user: String, token: String?, score: Int, count: Int, offset: Int): FeedbackPage =
            lovedPages.getOrNull(offset / count) ?: FeedbackPage(emptyList(), 0, 0)
    }

    @Test
    fun `migrated LastFm scrobbles with CSV-shifted dates and minute-truncated LB timestamps produce zero duplicates`() = runBlocking {
        val studio = track("drive:studio", "Opeth", "Windowpane", album = "Damnation")
        val compilation = track("drive:comp", "Opeth", "Windowpane", album = "Best Of", mbid = "mbid-comp")
        val judas = track("drive:judas", "Judas Priest", "Rock Hard Ride Free", album = "Defenders of the Faith")
        val inflames = track("drive:if", "In Flames", "Leeches", album = "Come Clarity")
        val freshTrack = track("drive:fresh", "Nightwish", "Nemo", album = "Once")

        // 1. Exact uts match whose play_history was shifted to 2015 by HistoricalPlayImporter
        val exactUts = 1_794_000_000L
        val csv2015Ms = 1_433_116_800_000L
        // 2. Two Last.fm scrobbles inside the same minute (1794951742 and 1794951755)
        //    both truncated to 1794951720 (:00) by ListenBrainz's Last.fm importer!
        val lfmMinuteBase = 1_794_951_720L
        val lastFmDao = FakeLastFmDao(
            mutableListOf(
                LastFmScrobble("kike", exactUts, "Opeth", "Windowpane", "Damnation", null, studio.id),
                LastFmScrobble("kike", lfmMinuteBase + 22, "Judas Priest", "Rock Hard Ride Free", "Defenders of the Faith", null, judas.id),
                LastFmScrobble("kike", lfmMinuteBase + 35, "In Flames", "Leeches", "Come Clarity", null, inflames.id),
            )
        )
        val historyDao = FakeHistoryDao(
            listOf(
                PlayHistory(id = 1, trackId = studio.id, playedAt = csv2015Ms, listenedMs = 240_000L, counted = true),
                PlayHistory(id = 2, trackId = judas.id, playedAt = (lfmMinuteBase + 22) * 1000L, listenedMs = 240_000L, counted = true),
                PlayHistory(id = 3, trackId = inflames.id, playedAt = (lfmMinuteBase + 35) * 1000L, listenedMs = 240_000L, counted = true),
            )
        )
        val listenDao = FakeListenDao()
        val newUts = 1_795_500_000L
        val api = FakeApi(
            totalCount = 4,
            pagesByMaxTs = {
                listOf(
                    RemoteListen(uts = newUts, artist = "Nightwish", track = "Nemo", album = "Once", mbid = null, msid = "msid-4"),
                    RemoteListen(uts = lfmMinuteBase, artist = "Judas Priest", track = "Rock Hard Ride Free", album = "Defenders of the Faith", mbid = null, msid = "msid-2"),
                    RemoteListen(uts = lfmMinuteBase, artist = "In Flames", track = "Leeches", album = "Come Clarity", mbid = null, msid = "msid-3"),
                    RemoteListen(uts = exactUts, artist = "Opeth", track = "Windowpane", album = "Best Of", mbid = "mbid-comp", msid = "msid-1"),
                )
            },
        )
        var importedTotal = -1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val importer = ListenBrainzImporter(
            client = api,
            listens = listenDao,
            lastFmScrobbles = lastFmDao,
            history = historyDao,
            tracks = fakeTrackDao(listOf(studio, compilation, judas, inflames, freshTrack)),
            favourites = FakeFavouriteDao(),
            flushOutbox = {},
            scope = scope,
            currentUser = { "kike" },
            currentToken = { "tok" },
            getImportedTotal = { importedTotal },
            setImportedTotal = { importedTotal = it },
            requestIntervalMs = 0L,
        )

        importer.import(everything = true)?.join()

        // All 4 listens stored (including BOTH same-minute listens at lfmMinuteBase via distinct msid!)
        assertEquals(4, listenDao.rows.size)
        // Only the 1 genuinely new Nightwish listen added to play_history (3 existing + 1 new = 4)!
        assertEquals(4, historyDao.plays.size)
        assertEquals(1, historyDao.plays.count { it.trackId == studio.id })
        assertEquals(0, historyDao.plays.count { it.trackId == compilation.id })
        assertEquals(1, historyDao.plays.count { it.trackId == judas.id })
        assertEquals(1, historyDao.plays.count { it.trackId == inflames.id })
        assertEquals(1, historyDao.plays.count { it.trackId == freshTrack.id && it.playedAt == newUts * 1000 })

        // Original studio trackId preserved rather than re-matched to compilation via mbid_mapping.
        assertEquals(studio.id, listenDao.rows[Triple("kike", exactUts, "msid-1")]?.trackId)
        assertEquals(judas.id, listenDao.rows[Triple("kike", lfmMinuteBase, "msid-2")]?.trackId)
        assertEquals(inflames.id, listenDao.rows[Triple("kike", lfmMinuteBase, "msid-3")]?.trackId)
    }

    @Test
    fun `brand new ListenBrainz user preserves distinct songs sharing a minute-truncated uts while deduping same-track repeats`() = runBlocking {
        val t1 = track("drive:1", "In Flames", "Only for the Weak", album = "Clayman")
        val t2 = track("drive:2", "Passenger", "Let Her Go", album = "All the Little Lights")
        val listenDao = FakeListenDao()
        val historyDao = FakeHistoryDao(
            listOf(PlayHistory(id = 1, trackId = t1.id, playedAt = 1_795_000_000_456L, listenedMs = 240_000L, counted = true))
        )
        val sharedMin = 1_794_900_000L
        val api = FakeApi(
            totalCount = 3,
            pagesByMaxTs = {
                listOf(
                    // Same track & same second as existing in-app play -> must NOT duplicate
                    RemoteListen(uts = 1_795_000_000L, artist = "In Flames", track = "Only for the Weak", album = "Clayman", mbid = null, msid = "m1"),
                    // Two distinct songs sharing the same minute-truncated uts -> BOTH must be inserted!
                    RemoteListen(uts = sharedMin, artist = "In Flames", track = "Only for the Weak", album = "Clayman", mbid = null, msid = "m2"),
                    RemoteListen(uts = sharedMin, artist = "Passenger", track = "Let Her Go", album = "All the Little Lights", mbid = null, msid = "m3"),
                )
            },
        )
        var importedTotal = -1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val importer = ListenBrainzImporter(
            client = api,
            listens = listenDao,
            lastFmScrobbles = FakeLastFmDao(),
            history = historyDao,
            tracks = fakeTrackDao(listOf(t1, t2)),
            favourites = FakeFavouriteDao(),
            flushOutbox = {},
            scope = scope,
            currentUser = { "family_member" },
            currentToken = { "tok" },
            getImportedTotal = { importedTotal },
            setImportedTotal = { importedTotal = it },
            requestIntervalMs = 0L,
        )

        importer.import(everything = true)?.join()

        assertEquals(3, listenDao.rows.size)
        assertEquals(3, historyDao.plays.size)
        assertTrue(historyDao.plays.any { it.trackId == t1.id && it.playedAt == sharedMin * 1000L })
        assertTrue(historyDao.plays.any { it.trackId == t2.id && it.playedAt == sharedMin * 1000L })
    }

    @Test
    fun `importLoved resolves null track_metadata via recording metadata endpoint and skips cross-source duplicates`() = runBlocking {
        val driveCopy = track("drive:ice", "Within Temptation", "Ice Queen", album = "Mother Earth", source = Track.SOURCE_DRIVE)
        val serverCopy = track("subsonic:ice", "Within Temptation", "Ice Queen", album = "Mother Earth", mbid = "mbid-ice", source = Track.SOURCE_SUBSONIC)
        val amaranth = track("drive:amaranth", "Nightwish", "Amaranth", album = "Dark Passion Play", mbid = null, source = Track.SOURCE_DRIVE)

        val favDao = FakeFavouriteDao(listOf(Favourite(driveCopy.id, 1_700_000_000_000L)))
        val api = FakeApi(
            lovedPages = listOf(
                FeedbackPage(
                    items = listOf(
                        // Already favourited via driveCopy -> skipped by cross-source songKey
                        RemoteFeedback(uts = 1_710_000_000L, artist = "Within Temptation", track = "Ice Queen", album = "Mother Earth", mbid = "mbid-ice", msid = null, score = 1),
                        // track_metadata was null on ListenBrainz -> resolved via getRecordingMetadata!
                        RemoteFeedback(uts = 1_720_000_000L, artist = null, track = null, album = null, mbid = "mbid-amaranth", msid = null, score = 1),
                    ),
                    totalCount = 2,
                    count = 2,
                )
            ),
            recordingMeta = mapOf(
                "mbid-amaranth" to MetadataLookupResult("mbid-amaranth", null, "Nightwish", "Amaranth", "Dark Passion Play")
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val importer = ListenBrainzImporter(
            client = api,
            listens = FakeListenDao(),
            lastFmScrobbles = FakeLastFmDao(),
            history = FakeHistoryDao(),
            tracks = fakeTrackDao(listOf(driveCopy, serverCopy, amaranth)),
            favourites = favDao,
            flushOutbox = {},
            scope = scope,
            currentUser = { "kike" },
            currentToken = { "tok" },
            getImportedTotal = { -1 },
            setImportedTotal = {},
            requestIntervalMs = 0L,
        )

        importer.importLoved()?.join()

        assertEquals(setOf(driveCopy.id, amaranth.id), favDao.map.keys)
    }
}
