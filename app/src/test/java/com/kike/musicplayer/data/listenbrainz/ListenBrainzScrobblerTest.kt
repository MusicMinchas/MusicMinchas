package com.kike.musicplayer.data.listenbrainz

import com.kike.musicplayer.data.db.ListenBrainzOutboxDao
import com.kike.musicplayer.data.db.ListenBrainzOutboxItem
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class ListenBrainzScrobblerTest {

    private class FakeOutboxDao : ListenBrainzOutboxDao {
        private var nextId = 1L
        val items = MutableStateFlow<List<ListenBrainzOutboxItem>>(emptyList())

        override suspend fun insert(item: ListenBrainzOutboxItem) {
            items.value = items.value + item.copy(id = nextId++)
        }

        override suspend fun pending(action: String, limit: Int): List<ListenBrainzOutboxItem> =
            items.value.filter { it.action == action }.sortedBy { it.id }.take(limit)

        override suspend fun pendingLoves(limit: Int): List<ListenBrainzOutboxItem> =
            items.value.filter { it.action == ListenBrainzOutboxItem.LOVE || it.action == ListenBrainzOutboxItem.UNLOVE }
                .sortedBy { it.id }.take(limit)

        override suspend fun delete(ids: List<Long>) {
            val drop = ids.toSet()
            items.value = items.value.filterNot { it.id in drop }
        }

        override fun observeCount(action: String): Flow<Int> =
            items.map { list -> list.count { it.action == action } }
    }

    private fun fakeTrackDao(map: Map<String, Track>): TrackDao =
        Proxy.newProxyInstance(TrackDao::class.java.classLoader, arrayOf(TrackDao::class.java)) { _, method, args ->
            when (method.name) {
                "getByIdsChunk" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args[0] as List<String>
                    ids.mapNotNull { map[it] }
                }
                else -> error("Unexpected TrackDao call: ${method.name}")
            }
        } as TrackDao

    private open class StubApiClient : ListenBrainzApiClient {
        val submitted = mutableListOf<Pair<String, List<ListenSubmission>>>()
        val feedbacks = mutableListOf<Pair<String?, Int>>()
        val lookups = mutableListOf<Triple<String, String, String?>>()
        var submitError: Exception? = null
        var feedbackError: Exception? = null
        var lookupResult: ((String, String, String?) -> MetadataLookupResult?) = { _, _, _ -> null }

        override suspend fun validateToken(token: String) = TokenValidation(true, "user")
        override suspend fun submitListens(token: String, listenType: String, submissions: List<ListenSubmission>) {
            submitError?.let { throw it }
            submitted += listenType to submissions
        }
        override suspend fun getListenCount(user: String, token: String?) = 0
        override suspend fun getListens(user: String, token: String?, maxTs: Long?, count: Int) = ListensPage(emptyList())
        override suspend fun lookupMetadata(artist: String, recording: String, release: String?, token: String?): MetadataLookupResult? {
            lookups += Triple(artist, recording, release)
            return lookupResult(artist, recording, release)
        }
        override suspend fun submitFeedback(token: String, recordingMbid: String?, recordingMsid: String?, score: Int) {
            feedbackError?.let { throw it }
            feedbacks += recordingMbid to score
        }
        override suspend fun getFeedback(user: String, token: String?, score: Int, count: Int, offset: Int) = FeedbackPage(emptyList(), 0, 0)
    }

    @Test
    fun `single listen sends single type and multiple queued listens batch as import`() = runBlocking {
        val outbox = FakeOutboxDao()
        val api = StubApiClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scrobbler = ListenBrainzScrobbler(
            client = api,
            outbox = outbox,
            tracks = fakeTrackDao(emptyMap()),
            scope = scope,
            userToken = { "tok" },
            isScrobblingEnabled = { true },
            onTokenInvalid = {},
        )

        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.SCROBBLE, artist = "A", track = "T1", album = null, albumArtist = null, durationMs = 200_000, trackNumber = 1, mbRecordingId = "mb1", mbReleaseId = null, timestamp = 1000))
        scrobbler.send()
        assertEquals(1, api.submitted.size)
        assertEquals(ListenBrainzApiClient.LISTEN_TYPE_SINGLE, api.submitted[0].first)
        assertTrue(outbox.items.value.isEmpty())

        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.SCROBBLE, artist = "A", track = "T2", album = null, albumArtist = null, durationMs = 200_000, trackNumber = 2, mbRecordingId = null, mbReleaseId = null, timestamp = 2000))
        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.SCROBBLE, artist = "B", track = "T3", album = null, albumArtist = null, durationMs = 200_000, trackNumber = 3, mbRecordingId = null, mbReleaseId = null, timestamp = 3000))
        scrobbler.send()
        assertEquals(2, api.submitted.size)
        assertEquals(ListenBrainzApiClient.LISTEN_TYPE_IMPORT, api.submitted[1].first)
        assertEquals(2, api.submitted[1].second.size)
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `401 and temporary errors keep outbox rows while 400 drops malformed batch`() = runBlocking {
        val outbox = FakeOutboxDao()
        val api = StubApiClient()
        var invalidated = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scrobbler = ListenBrainzScrobbler(
            client = api,
            outbox = outbox,
            tracks = fakeTrackDao(emptyMap()),
            scope = scope,
            userToken = { "tok" },
            isScrobblingEnabled = { true },
            onTokenInvalid = { invalidated++ },
        )

        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.SCROBBLE, artist = "A", track = "T1", album = null, albumArtist = null, durationMs = null, trackNumber = null, mbRecordingId = null, mbReleaseId = null, timestamp = 1000))

        // 401 keeps outbox and notifies invalid token
        api.submitError = ListenBrainzException(401, "Invalid token")
        scrobbler.send()
        assertEquals(1, invalidated)
        assertEquals(1, outbox.items.value.size)

        // IOException keeps outbox
        api.submitError = IOException("offline")
        scrobbler.send()
        assertEquals(1, outbox.items.value.size)

        // 429 keeps outbox
        api.submitError = ListenBrainzException(429, "rate limited", retryAfterSec = 60)
        scrobbler.send()
        assertEquals(1, outbox.items.value.size)

        // 400 drops malformed batch
        api.submitError = ListenBrainzException(400, "bad payload")
        scrobbler.send()
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `sendLoves resolves missing MBID via metadata lookup fallback and drops unmapped loves`() = runBlocking {
        val outbox = FakeOutboxDao()
        val api = StubApiClient()
        api.lookupResult = { artist, track, release ->
            when {
                artist == "Opeth" && track == "Windowpane" && release == null -> MetadataLookupResult("mbid-resolved", null, "Opeth", "Windowpane")
                else -> null
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scrobbler = ListenBrainzScrobbler(
            client = api,
            outbox = outbox,
            tracks = fakeTrackDao(emptyMap()),
            scope = scope,
            userToken = { "tok" },
            isScrobblingEnabled = { true },
            onTokenInvalid = {},
        )

        // 1. Direct MBID
        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.LOVE, artist = "A", track = "T1", album = "Alb", albumArtist = null, durationMs = null, trackNumber = null, mbRecordingId = "mbid-direct", mbReleaseId = null, timestamp = 100))
        // 2. Missing MBID, fails with release name, succeeds without release name
        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.UNLOVE, artist = "Opeth", track = "Windowpane", album = "Damnation (Remaster)", albumArtist = null, durationMs = null, trackNumber = null, mbRecordingId = null, mbReleaseId = null, timestamp = 200))
        // 3. Unmapped personal recording -> skipped cleanly
        outbox.insert(ListenBrainzOutboxItem(action = ListenBrainzOutboxItem.LOVE, artist = "Garage Band", track = "Rehearsal 1", album = null, albumArtist = null, durationMs = null, trackNumber = null, mbRecordingId = null, mbReleaseId = null, timestamp = 300))

        scrobbler.send()

        assertTrue(outbox.items.value.isEmpty())
        assertEquals(listOf("mbid-direct" to 1, "mbid-resolved" to 0), api.feedbacks)
        assertEquals(3, api.lookups.size)
    }
}
