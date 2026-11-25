package com.kike.musicplayer.data.listenbrainz

import com.kike.musicplayer.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** An HTTP or API error returned by ListenBrainz (https://listenbrainz.readthedocs.io/). */
class ListenBrainzException(
    val statusCode: Int,
    message: String,
    val retryAfterSec: Int? = null,
) : IOException("ListenBrainz HTTP $statusCode: $message") {
    val isAuthError get() = statusCode == 401
    val isRateLimited get() = statusCode == 429
    val isTemporary get() = statusCode == 429 || statusCode >= 500
}

/** Rate-limit hints returned in ListenBrainz HTTP response headers. */
data class RateLimitInfo(val remaining: Int? = null, val resetInSec: Int? = null)

data class TokenValidation(val valid: Boolean, val userName: String?, val message: String? = null)

data class ListenSubmission(
    val artist: String,
    val track: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val mbRecordingId: String? = null,
    val mbReleaseId: String? = null,
    val timestampSec: Long? = null,
)

data class RemoteListen(
    val uts: Long,
    val artist: String,
    val track: String,
    val album: String?,
    val mbid: String?,
    val msid: String?,
)

data class ListensPage(
    val listens: List<RemoteListen>,
    val rateLimit: RateLimitInfo = RateLimitInfo(),
)

data class RemoteFeedback(
    val uts: Long,
    val artist: String?,
    val track: String?,
    val album: String?,
    val mbid: String?,
    val msid: String?,
    val score: Int,
)

data class FeedbackPage(
    val items: List<RemoteFeedback>,
    val totalCount: Int,
    val count: Int,
    val rateLimit: RateLimitInfo = RateLimitInfo(),
)

data class MetadataLookupResult(
    val recordingMbid: String?,
    val releaseMbid: String?,
    val artistCreditName: String?,
    val recordingName: String?,
    val releaseName: String? = null,
)

/** Operations against the ListenBrainz v1 HTTP API, abstracted for unit testing. */
interface ListenBrainzApiClient {
    suspend fun validateToken(token: String): TokenValidation
    suspend fun submitListens(token: String, listenType: String, submissions: List<ListenSubmission>)
    suspend fun getListenCount(user: String, token: String? = null): Int
    suspend fun getListens(user: String, token: String? = null, maxTs: Long? = null, count: Int = MAX_ITEMS_PER_GET): ListensPage
    suspend fun lookupMetadata(artist: String, recording: String, release: String? = null, token: String? = null): MetadataLookupResult?
    suspend fun getRecordingMetadata(mbids: List<String>, token: String? = null): Map<String, MetadataLookupResult> = emptyMap()
    suspend fun submitFeedback(token: String, recordingMbid: String? = null, recordingMsid: String? = null, score: Int)
    suspend fun getFeedback(
        user: String,
        token: String? = null,
        score: Int = 1,
        count: Int = DEFAULT_FEEDBACK_PAGE,
        offset: Int = 0,
    ): FeedbackPage

    companion object {
        const val LISTEN_TYPE_PLAYING_NOW = "playing_now"
        const val LISTEN_TYPE_SINGLE = "single"
        const val LISTEN_TYPE_IMPORT = "import"
        const val SCORE_LOVE = 1
        const val SCORE_REMOVE = 0
        const val MAX_ITEMS_PER_GET = 1000
        const val DEFAULT_FEEDBACK_PAGE = 100
    }
}

/**
 * ListenBrainz web service client (https://api.listenbrainz.org/1/).
 * Authenticated requests pass `Authorization: Token <user_token>`.
 */
class ListenBrainzClient(
    private val http: OkHttpClient,
    val moshi: Moshi,
    private val baseUrl: String = BASE_URL,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) : ListenBrainzApiClient {

    private val mapAdapter = moshi.adapter(Map::class.java)
    private val userAgent = "MusicMinchas/$appVersion (personal Android app)"

    override suspend fun validateToken(token: String): TokenValidation = withContext(Dispatchers.IO) {
        val url = "${baseUrl}validate-token"
        val (body, _) = execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Token $token")
                .header("User-Agent", userAgent)
                .get()
                .build(),
            allow401Json = true,
        )
        parseTokenValidation(body, moshi)
    }

    override suspend fun submitListens(token: String, listenType: String, submissions: List<ListenSubmission>) {
        if (submissions.isEmpty()) return
        withContext(Dispatchers.IO) {
            val json = buildSubmitListensJson(listenType, submissions, appVersion, moshi)
            execute(
                Request.Builder()
                    .url("${baseUrl}submit-listens")
                    .header("Authorization", "Token $token")
                    .header("User-Agent", userAgent)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        }
    }

    override suspend fun getListenCount(user: String, token: String?): Int = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("user")
            .addPathSegment(user)
            .addPathSegment("listen-count")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            token?.let { header("Authorization", "Token $it") }
        }.get().build()
        val (body, _) = execute(req)
        parseListenCount(body, moshi)
    }

    override suspend fun getListens(user: String, token: String?, maxTs: Long?, count: Int): ListensPage = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("user")
            .addPathSegment(user)
            .addPathSegment("listens")
            .addQueryParameter("count", count.coerceIn(1, ListenBrainzApiClient.MAX_ITEMS_PER_GET).toString())
            .apply { maxTs?.let { addQueryParameter("max_ts", it.toString()) } }
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            token?.let { header("Authorization", "Token $it") }
        }.get().build()
        val (body, rateLimit) = execute(req)
        ListensPage(parseListens(body, moshi), rateLimit)
    }

    override suspend fun lookupMetadata(
        artist: String,
        recording: String,
        release: String?,
        token: String?,
    ): MetadataLookupResult? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("metadata")
            .addPathSegment("lookup")
            .addPathSegment("") // trailing slash avoids 308 redirect stripping query/headers
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("recording_name", recording)
            .apply { release?.takeIf { it.isNotBlank() }?.let { addQueryParameter("release_name", it) } }
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            token?.let { header("Authorization", "Token $it") }
        }.get().build()
        val (body, _) = execute(req)
        parseMetadataLookup(body, moshi)
    }

    override suspend fun getRecordingMetadata(
        mbids: List<String>,
        token: String?,
    ): Map<String, MetadataLookupResult> = withContext(Dispatchers.IO) {
        val clean = mbids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return@withContext emptyMap()
        val result = HashMap<String, MetadataLookupResult>()
        for (chunk in clean.chunked(RECORDING_METADATA_BATCH)) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("metadata")
                .addPathSegment("recording")
                .addPathSegment("")
                .addQueryParameter("recording_mbids", chunk.joinToString(","))
                .addQueryParameter("inc", "artist release")
                .build()
            val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
                token?.let { header("Authorization", "Token $it") }
            }.get().build()
            val (body, _) = execute(req)
            result.putAll(parseRecordingMetadata(body, moshi))
        }
        result
    }

    override suspend fun submitFeedback(token: String, recordingMbid: String?, recordingMsid: String?, score: Int) {
        require(!recordingMbid.isNullOrBlank() || !recordingMsid.isNullOrBlank()) {
            "recording_mbid or recording_msid is required"
        }
        withContext(Dispatchers.IO) {
            val json = buildFeedbackJson(recordingMbid, recordingMsid, score, moshi)
            execute(
                Request.Builder()
                    .url("${baseUrl}feedback/recording-feedback")
                    .header("Authorization", "Token $token")
                    .header("User-Agent", userAgent)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        }
    }

    override suspend fun getFeedback(
        user: String,
        token: String?,
        score: Int,
        count: Int,
        offset: Int,
    ): FeedbackPage = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("feedback")
            .addPathSegment("user")
            .addPathSegment(user)
            .addPathSegment("get-feedback")
            .addQueryParameter("score", score.toString())
            .addQueryParameter("count", count.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("metadata", "true")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            token?.let { header("Authorization", "Token $it") }
        }.get().build()
        val (body, rateLimit) = execute(req)
        parseFeedbackPage(body, rateLimit, moshi)
    }

    private fun execute(request: Request, allow401Json: Boolean = false): Pair<String, RateLimitInfo> {
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            val rateLimit = RateLimitInfo(
                remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull(),
                resetInSec = response.header("X-RateLimit-Reset-In")?.toIntOrNull(),
            )
            if (allow401Json && response.code == 401 && body.trimStart().startsWith("{")) {
                return body to rateLimit
            }
            if (!response.isSuccessful) {
                val errMsg = extractErrorMessage(body) ?: response.message.ifBlank { "Request failed" }
                throw ListenBrainzException(response.code, errMsg, retryAfterSec = rateLimit.resetInSec)
            }
            return body to rateLimit
        }
    }

    private fun extractErrorMessage(body: String): String? = runCatching {
        val map = mapAdapter.fromJson(body) ?: return@runCatching null
        (map["error"] as? String) ?: (map["message"] as? String)
    }.getOrNull()

    companion object {
        const val BASE_URL = "https://api.listenbrainz.org/1/"
        const val SETTINGS_URL = "https://listenbrainz.org/settings/"
        private const val RECORDING_METADATA_BATCH = 25
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * Returns [value] trimmed if it is a valid 36-character MusicBrainz UUID, or `null` otherwise.
         * Guards against non-UUID IDs (e.g. Deezer/Apple catalog album IDs stored in `Track.mbReleaseId`)
         * that would cause ListenBrainz `/1/submit-listens` or `/1/feedback` to reject the request with HTTP 400.
         */
        fun validMbidOrNull(value: String?): String? =
            value?.trim()?.takeIf { UUID_REGEX.matches(it) }

        fun buildSubmitListensJson(
            listenType: String,
            submissions: List<ListenSubmission>,
            appVersion: String,
            moshi: Moshi,
        ): String {
            val payload = submissions.map { s ->
                buildMap<String, Any> {
                    if (listenType != ListenBrainzApiClient.LISTEN_TYPE_PLAYING_NOW) {
                        s.timestampSec?.let { put("listened_at", it) }
                    }
                    put(
                        "track_metadata",
                        buildMap<String, Any> {
                            put("artist_name", s.artist)
                            put("track_name", s.track)
                            s.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                            put(
                                "additional_info",
                                buildMap<String, Any> {
                                    put("media_player", "MusicMinchas")
                                    put("submission_client", "MusicMinchas")
                                    put("submission_client_version", appVersion)
                                    s.durationMs?.takeIf { it > 0 }?.let { put("duration_ms", it) }
                                    s.trackNumber?.takeIf { it > 0 }?.let { put("tracknumber", it) }
                                    validMbidOrNull(s.mbRecordingId)?.let { put("recording_mbid", it) }
                                    validMbidOrNull(s.mbReleaseId)?.let { put("release_mbid", it) }
                                }
                            )
                        }
                    )
                }
            }
            val root = mapOf("listen_type" to listenType, "payload" to payload)
            return moshi.adapter(Map::class.java).toJson(root)
        }

        fun buildFeedbackJson(recordingMbid: String?, recordingMsid: String?, score: Int, moshi: Moshi): String {
            val root = buildMap<String, Any> {
                recordingMbid?.takeIf { it.isNotBlank() }?.let { put("recording_mbid", it) }
                recordingMsid?.takeIf { it.isNotBlank() }?.let { put("recording_msid", it) }
                put("score", score)
            }
            return moshi.adapter(Map::class.java).toJson(root)
        }

        fun parseTokenValidation(json: String, moshi: Moshi): TokenValidation {
            val map = moshi.adapter(Map::class.java).fromJson(json)
                ?: throw IOException("Unexpected ListenBrainz response")
            val valid = map["valid"] == true
            val user = (map["user_name"] as? String)?.takeIf { it.isNotBlank() }
            val msg = (map["message"] as? String)?.takeIf { it.isNotBlank() }
            return TokenValidation(valid = valid, userName = user, message = msg)
        }

        fun parseListenCount(json: String, moshi: Moshi): Int {
            val map = moshi.adapter(Map::class.java).fromJson(json)
                ?: throw IOException("Unexpected ListenBrainz response")
            val payload = map["payload"] as? Map<*, *> ?: throw IOException("Unexpected ListenBrainz response")
            return (payload["count"] as? Number)?.toInt() ?: throw IOException("Missing listen count")
        }

        fun parseListens(json: String, moshi: Moshi): List<RemoteListen> {
            val map = moshi.adapter(Map::class.java).fromJson(json)
                ?: throw IOException("Unexpected ListenBrainz response")
            val payload = map["payload"] as? Map<*, *> ?: throw IOException("Unexpected ListenBrainz response")
            val items = payload["listens"] as? List<*> ?: return emptyList()
            return items.mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val uts = (m["listened_at"] as? Number)?.toLong() ?: return@mapNotNull null
                val meta = m["track_metadata"] as? Map<*, *> ?: return@mapNotNull null
                val mapping = meta["mbid_mapping"] as? Map<*, *>
                val extra = meta["additional_info"] as? Map<*, *>
                val artist = (meta["artist_name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("artist_credit_name") as? String)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val track = (meta["track_name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("recording_name") as? String)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val album = (meta["release_name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("release_name") as? String)?.takeIf { it.isNotBlank() }
                val mbid = (mapping?.get("recording_mbid") as? String)?.takeIf { it.isNotBlank() }
                    ?: (extra?.get("recording_mbid") as? String)?.takeIf { it.isNotBlank() }
                val msid = (m["recording_msid"] as? String)?.takeIf { it.isNotBlank() }
                RemoteListen(uts = uts, artist = artist, track = track, album = album, mbid = mbid, msid = msid)
            }
        }

        fun parseMetadataLookup(json: String, moshi: Moshi): MetadataLookupResult? {
            val map = moshi.adapter(Map::class.java).fromJson(json) ?: return null
            val recMbid = (map["recording_mbid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return MetadataLookupResult(
                recordingMbid = recMbid,
                releaseMbid = (map["release_mbid"] as? String)?.takeIf { it.isNotBlank() },
                artistCreditName = (map["artist_credit_name"] as? String)?.takeIf { it.isNotBlank() },
                recordingName = (map["recording_name"] as? String)?.takeIf { it.isNotBlank() },
                releaseName = (map["release_name"] as? String)?.takeIf { it.isNotBlank() },
            )
        }

        fun parseRecordingMetadata(json: String, moshi: Moshi): Map<String, MetadataLookupResult> {
            val map = moshi.adapter(Map::class.java).fromJson(json) ?: return emptyMap()
            val out = HashMap<String, MetadataLookupResult>()
            for ((k, v) in map) {
                val mbid = k as? String ?: continue
                val entry = v as? Map<*, *> ?: continue
                val artist = ((entry["artist"] as? Map<*, *>)?.get("name") as? String)?.takeIf { it.isNotBlank() }
                val rec = ((entry["recording"] as? Map<*, *>)?.get("name") as? String)?.takeIf { it.isNotBlank() }
                val rel = entry["release"] as? Map<*, *>
                val relName = (rel?.get("name") as? String)?.takeIf { it.isNotBlank() }
                val relMbid = (rel?.get("mbid") as? String)?.takeIf { it.isNotBlank() }
                if (artist != null && rec != null) {
                    out[mbid] = MetadataLookupResult(
                        recordingMbid = mbid,
                        releaseMbid = relMbid,
                        artistCreditName = artist,
                        recordingName = rec,
                        releaseName = relName,
                    )
                }
            }
            return out
        }

        fun parseFeedbackPage(json: String, rateLimit: RateLimitInfo, moshi: Moshi): FeedbackPage {
            val map = moshi.adapter(Map::class.java).fromJson(json)
                ?: throw IOException("Unexpected ListenBrainz feedback response")
            val total = (map["total_count"] as? Number)?.toInt() ?: 0
            val count = (map["count"] as? Number)?.toInt() ?: 0
            val list = map["feedback"] as? List<*> ?: emptyList<Any>()
            val items = list.mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val score = (m["score"] as? Number)?.toInt() ?: return@mapNotNull null
                val meta = m["track_metadata"] as? Map<*, *>
                val mapping = meta?.get("mbid_mapping") as? Map<*, *>
                val artist = (meta?.get("artist_name") as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("artist_credit_name") as? String)?.takeIf { it.isNotBlank() }
                val track = (meta?.get("track_name") as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("recording_name") as? String)?.takeIf { it.isNotBlank() }
                val album = (meta?.get("release_name") as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("release_name") as? String)?.takeIf { it.isNotBlank() }
                val mbid = (m["recording_mbid"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (mapping?.get("recording_mbid") as? String)?.takeIf { it.isNotBlank() }
                val msid = (m["recording_msid"] as? String)?.takeIf { it.isNotBlank() }
                if (mbid == null && (artist == null || track == null)) return@mapNotNull null
                val uts = parseTimestampSec(m["created"]) ?: (System.currentTimeMillis() / 1000)
                RemoteFeedback(
                    uts = uts,
                    artist = artist,
                    track = track,
                    album = album,
                    mbid = mbid,
                    msid = msid,
                    score = score,
                )
            }
            return FeedbackPage(items = items, totalCount = total, count = count, rateLimit = rateLimit)
        }

        private fun parseTimestampSec(raw: Any?): Long? = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: runCatching { Instant.parse(raw).epochSecond }.getOrNull()
            else -> null
        }
    }
}
