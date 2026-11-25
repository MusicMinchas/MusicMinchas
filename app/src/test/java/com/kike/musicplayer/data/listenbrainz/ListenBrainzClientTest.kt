package com.kike.musicplayer.data.listenbrainz

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ListenBrainzClientTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Test
    fun `playing_now payload omits listened_at and includes additional_info`() {
        val recUuid = "1145a0eb-065b-47c3-87cc-58ed204e1bc2"
        val relUuid = "95bc4c8a-878a-4a39-bf18-b967c12b6ab2"
        val json = ListenBrainzClient.buildSubmitListensJson(
            listenType = ListenBrainzApiClient.LISTEN_TYPE_PLAYING_NOW,
            submissions = listOf(
                ListenSubmission(
                    artist = "Opeth",
                    track = "Windowpane",
                    album = "Damnation",
                    durationMs = 464_000L,
                    trackNumber = 1,
                    mbRecordingId = recUuid,
                    mbReleaseId = relUuid,
                    timestampSec = 1_700_000_000L,
                )
            ),
            appVersion = "1.50.2",
            moshi = moshi,
        )
        val root = moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
        assertEquals("playing_now", root["listen_type"])
        val payload = root["payload"] as List<*>
        val entry = payload.single() as Map<*, *>
        assertFalse("playing_now must omit listened_at", entry.containsKey("listened_at"))
        val meta = entry["track_metadata"] as Map<*, *>
        assertEquals("Opeth", meta["artist_name"])
        assertEquals("Windowpane", meta["track_name"])
        assertEquals("Damnation", meta["release_name"])
        val extra = meta["additional_info"] as Map<*, *>
        assertEquals("MusicMinchas", extra["media_player"])
        assertEquals("MusicMinchas", extra["submission_client"])
        assertEquals("1.50.2", extra["submission_client_version"])
        assertEquals(464_000.0, (extra["duration_ms"] as Number).toDouble(), 0.0)
        assertEquals(1.0, (extra["tracknumber"] as Number).toDouble(), 0.0)
        assertEquals(recUuid, extra["recording_mbid"])
        assertEquals(relUuid, extra["release_mbid"])
    }

    @Test
    fun `submit payload omits non-UUID recording_mbid and release_mbid`() {
        val json = ListenBrainzClient.buildSubmitListensJson(
            listenType = ListenBrainzApiClient.LISTEN_TYPE_SINGLE,
            submissions = listOf(
                ListenSubmission(
                    artist = "Ghost",
                    track = "Cirice",
                    album = "Meliora",
                    mbRecordingId = "not-a-uuid",
                    mbReleaseId = "302127",
                    timestampSec = 1_700_000_000L,
                )
            ),
            appVersion = "1.51.0",
            moshi = moshi,
        )
        val root = moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
        val entry = (root["payload"] as List<*>).single() as Map<*, *>
        val extra = (entry["track_metadata"] as Map<*, *>)["additional_info"] as Map<*, *>
        assertFalse("Non-UUID recording_mbid must be omitted", extra.containsKey("recording_mbid"))
        assertFalse("Non-UUID release_mbid (e.g. catalog albumId) must be omitted", extra.containsKey("release_mbid"))
    }

    @Test
    fun `import payload includes listened_at for every item`() {
        val json = ListenBrainzClient.buildSubmitListensJson(
            listenType = ListenBrainzApiClient.LISTEN_TYPE_IMPORT,
            submissions = listOf(
                ListenSubmission(artist = "A", track = "T1", timestampSec = 1000L),
                ListenSubmission(artist = "B", track = "T2", timestampSec = 2000L),
            ),
            appVersion = "1.50.2",
            moshi = moshi,
        )
        val root = moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
        assertEquals("import", root["listen_type"])
        val payload = root["payload"] as List<*>
        assertEquals(2, payload.size)
        assertEquals(1000L, ((payload[0] as Map<*, *>)["listened_at"] as Number).toLong())
        assertEquals(2000L, ((payload[1] as Map<*, *>)["listened_at"] as Number).toLong())
    }

    @Test
    fun `parseListens reads mbid_mapping and additional_info recording_mbid`() {
        val json = """
            {
              "payload": {
                "count": 2,
                "listens": [
                  {
                    "listened_at": 1700000100,
                    "recording_msid": "msid-1",
                    "track_metadata": {
                      "artist_name": "Nightwish",
                      "track_name": "Nemo",
                      "release_name": "Once",
                      "mbid_mapping": { "recording_mbid": "mbid-mapped-1" }
                    }
                  },
                  {
                    "listened_at": 1700000200,
                    "recording_msid": "msid-2",
                    "track_metadata": {
                      "artist_name": "Epica",
                      "track_name": "Cry for the Moon",
                      "additional_info": { "recording_mbid": "mbid-extra-2" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val listens = ListenBrainzClient.parseListens(json, moshi)
        assertEquals(2, listens.size)
        assertEquals(1700000100L, listens[0].uts)
        assertEquals("Nightwish", listens[0].artist)
        assertEquals("Nemo", listens[0].track)
        assertEquals("Once", listens[0].album)
        assertEquals("mbid-mapped-1", listens[0].mbid)
        assertEquals("msid-1", listens[0].msid)
        assertEquals("mbid-extra-2", listens[1].mbid)
        assertNull(listens[1].album)
    }

    @Test
    fun `parseFeedbackPage handles numeric and ISO timestamps`() {
        val json = """
            {
              "count": 2,
              "offset": 0,
              "total_count": 2,
              "feedback": [
                {
                  "created": 1700000500,
                  "recording_mbid": "mbid-1",
                  "score": 1,
                  "track_metadata": {
                    "artist_name": "Within Temptation",
                    "track_name": "Ice Queen",
                    "release_name": "Mother Earth"
                  }
                },
                {
                  "created": "2024-01-01T00:00:00Z",
                  "recording_mbid": "mbid-2",
                  "score": 1,
                  "track_metadata": {
                    "mbid_mapping": {
                      "artist_credit_name": "In Flames",
                      "recording_name": "Only for the Weak"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val page = ListenBrainzClient.parseFeedbackPage(json, RateLimitInfo(10, 5), moshi)
        assertEquals(2, page.totalCount)
        assertEquals(2, page.items.size)
        assertEquals(1700000500L, page.items[0].uts)
        assertEquals("Within Temptation", page.items[0].artist)
        assertEquals("Ice Queen", page.items[0].track)
        assertEquals(1704067200L, page.items[1].uts)
        assertEquals("In Flames", page.items[1].artist)
        assertEquals("Only for the Weak", page.items[1].track)
    }

    @Test
    fun `parseMetadataLookup handles hit and empty object`() {
        val hit = ListenBrainzClient.parseMetadataLookup(
            """{"recording_mbid":"abc-123","release_mbid":"rel-1","artist_credit_name":"Muse","recording_name":"Hysteria"}""",
            moshi,
        )
        assertEquals("abc-123", hit?.recordingMbid)
        assertEquals("rel-1", hit?.releaseMbid)
        assertNull(ListenBrainzClient.parseMetadataLookup("{}", moshi))
    }

    @Test
    fun `parseRecordingMetadata extracts artist recording and release`() {
        val json = """
            {
              "93d22b7d-0d05-4e60-aa5e-9741b0f14432": {
                "artist": { "name": "Nightwish" },
                "recording": { "name": "Amaranth" },
                "release": { "mbid": "f2f593cc-9773-41de-bb32-fbc197e2f5ba", "name": "Dark Passion Play" }
              }
            }
        """.trimIndent()
        val map = ListenBrainzClient.parseRecordingMetadata(json, moshi)
        val item = map["93d22b7d-0d05-4e60-aa5e-9741b0f14432"]!!
        assertEquals("Nightwish", item.artistCreditName)
        assertEquals("Amaranth", item.recordingName)
        assertEquals("Dark Passion Play", item.releaseName)
        assertEquals("f2f593cc-9773-41de-bb32-fbc197e2f5ba", item.releaseMbid)
    }

    @Test
    fun `client sends Token Authorization header and handles 401 and 429`() = runBlocking {
        var capturedAuth: String? = null
        var capturedUa: String? = null
        var nextStatus = 200
        var nextBody = """{"code":200,"valid":true,"user_name":"alice"}"""
        var nextReset: String? = null

        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedAuth = req.header("Authorization")
                capturedUa = req.header("User-Agent")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(nextStatus)
                    .message("Mock")
                    .apply { nextReset?.let { header("X-RateLimit-Reset-In", it) } }
                    .body(nextBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = ListenBrainzClient(http, moshi, appVersion = "1.51.0")

        // 1. validate-token success
        val valid = client.validateToken("secret-tok")
        assertTrue(valid.valid)
        assertEquals("alice", valid.userName)
        assertEquals("Token secret-tok", capturedAuth)
        assertTrue(capturedUa!!.startsWith("MusicMinchas/1.51.0"))

        // 2. validate-token returns 401 JSON -> parsed as invalid, not thrown
        nextStatus = 401
        nextBody = """{"code":401,"valid":false,"message":"Token invalid"}"""
        val invalid = client.validateToken("bad-tok")
        assertFalse(invalid.valid)
        assertEquals("Token invalid", invalid.message)

        // 3. submit-listens 429 -> ListenBrainzException with retryAfterSec
        nextStatus = 429
        nextReset = "14"
        nextBody = """{"code":429,"error":"Rate limit exceeded"}"""
        try {
            client.submitListens("secret-tok", ListenBrainzApiClient.LISTEN_TYPE_SINGLE, listOf(ListenSubmission("A", "B", timestampSec = 100)))
            fail("Expected ListenBrainzException")
        } catch (e: ListenBrainzException) {
            assertTrue(e.isRateLimited)
            assertTrue(e.isTemporary)
            assertEquals(14, e.retryAfterSec)
        }

        // 4. submit-listens 401 -> isAuthError
        nextStatus = 401
        nextReset = null
        nextBody = """{"code":401,"error":"Unauthorized"}"""
        try {
            client.submitListens("secret-tok", ListenBrainzApiClient.LISTEN_TYPE_SINGLE, listOf(ListenSubmission("A", "B", timestampSec = 100)))
            fail("Expected ListenBrainzException")
        } catch (e: ListenBrainzException) {
            assertTrue(e.isAuthError)
            assertFalse(e.isTemporary)
        }
    }
}
