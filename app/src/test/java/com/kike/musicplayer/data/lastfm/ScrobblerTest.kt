package com.kike.musicplayer.data.lastfm

import com.kike.musicplayer.data.db.LastFmOutboxDao
import com.kike.musicplayer.data.db.LastFmOutboxItem
import com.kike.musicplayer.data.db.TrackDao
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class ScrobblerTest {

    private class FakeOutboxDao : LastFmOutboxDao {
        private var nextId = 1L
        val items = MutableStateFlow<List<LastFmOutboxItem>>(emptyList())

        override suspend fun insert(item: LastFmOutboxItem) {
            items.value = items.value + item.copy(id = nextId++)
        }

        override suspend fun pending(action: String, limit: Int): List<LastFmOutboxItem> =
            items.value.filter { it.action == action }.sortedBy { it.id }.take(limit)

        override suspend fun pendingLoves(limit: Int): List<LastFmOutboxItem> =
            items.value.filter { it.action == LastFmOutboxItem.LOVE || it.action == LastFmOutboxItem.UNLOVE }
                .sortedBy { it.id }.take(limit)

        override suspend fun delete(ids: List<Long>) {
            val drop = ids.toSet()
            items.value = items.value.filterNot { it.id in drop }
        }

        override fun observeCount(action: String): Flow<Int> =
            items.map { list -> list.count { it.action == action } }
    }

    private class StubLastFmClient(
        apiKeyProvider: () -> String,
        secretProvider: () -> String,
    ) : LastFmClient(
        http = OkHttpClient(),
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
        apiKeyProvider = apiKeyProvider,
        secretProvider = secretProvider,
    ) {
        var nextError: Exception? = null
        var calls = 0

        override suspend fun call(
            method: String,
            params: Map<String, String>,
            sessionKey: String?,
            signed: Boolean,
            post: Boolean,
        ): String {
            calls++
            nextError?.let { throw it }
            return """{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}"""
        }
    }

    private val unusedTrackDao: TrackDao =
        Proxy.newProxyInstance(TrackDao::class.java.classLoader, arrayOf(TrackDao::class.java)) { _, method, _ ->
            error("Unexpected TrackDao call: ${method.name}")
        } as TrackDao

    @Test
    fun `API key errors 10, 26, and 13 preserve queued scrobbles and loves instead of deleting them`() = runBlocking {
        val outbox = FakeOutboxDao()
        val client = StubLastFmClient({ "pro-key" }, { "pro-secret" })
        val rejected = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scrobbler = Scrobbler(
            client = client,
            outbox = outbox,
            tracks = unusedTrackDao,
            scope = scope,
            sessionKey = { "sk-123" },
            isScrobblingEnabled = { true },
            onSessionInvalid = {},
            onApiKeyRejected = { rejected += it },
        )

        outbox.insert(LastFmOutboxItem(action = LastFmOutboxItem.SCROBBLE, artist = "Amorphis", track = "The Bee", album = "Queen of Time", albumArtist = null, durationSec = 330, timestamp = 1_795_470_109L))
        outbox.insert(LastFmOutboxItem(action = LastFmOutboxItem.LOVE, artist = "Amorphis", track = "The Bee", album = "Queen of Time", albumArtist = null, durationSec = null, timestamp = 1_795_470_110L))

        for (code in listOf(LastFmException.INVALID_API_KEY, LastFmException.SUSPENDED_API_KEY, LastFmException.INVALID_SIGNATURE)) {
            client.nextError = LastFmException(code, "Key error $code")
            scrobbler.send()
            assertEquals("Outbox must stay intact on Last.fm error $code", 2, outbox.items.value.size)
        }
        assertEquals(6, rejected.size) // 2 calls (scrobble + love) per error code

        // Once the Pro key works again, both queued items flush cleanly.
        client.nextError = null
        scrobbler.send()
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `unconfigured client skips network and preserves outbox while malformed error 6 drops`() = runBlocking {
        val outbox = FakeOutboxDao()
        var configuredKey = ""
        val client = StubLastFmClient({ configuredKey }, { "secret" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scrobbler = Scrobbler(
            client = client,
            outbox = outbox,
            tracks = unusedTrackDao,
            scope = scope,
            sessionKey = { "sk-123" },
            isScrobblingEnabled = { true },
            onSessionInvalid = {},
            onApiKeyRejected = {},
        )

        outbox.insert(LastFmOutboxItem(action = LastFmOutboxItem.SCROBBLE, artist = "A", track = "B", album = null, albumArtist = null, durationSec = 200, timestamp = 1000L))

        // Unconfigured -> no network call, outbox kept
        scrobbler.send()
        assertEquals(0, client.calls)
        assertEquals(1, outbox.items.value.size)

        // IOException -> postponed, outbox kept
        configuredKey = "pro-key"
        client.nextError = IOException("offline")
        scrobbler.send()
        assertEquals(1, outbox.items.value.size)

        // Malformed request error 6 -> dropped cleanly
        client.nextError = LastFmException(6, "Invalid parameters")
        scrobbler.send()
        assertTrue(outbox.items.value.isEmpty())
    }
}
