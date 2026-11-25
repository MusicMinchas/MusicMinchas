package com.kike.musicplayer.data.listenbrainz

import android.util.Log
import com.kike.musicplayer.data.db.ListenBrainzOutboxDao
import com.kike.musicplayer.data.db.ListenBrainzOutboxItem
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.getByIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Sends plays, loves and unloves to ListenBrainz. Everything goes through a Room outbox table
 * (`listenbrainz_outbox`) first so offline listening and app restarts never lose scrobbles.
 */
class ListenBrainzScrobbler(
    private val client: ListenBrainzApiClient,
    private val outbox: ListenBrainzOutboxDao,
    private val tracks: TrackDao,
    private val scope: CoroutineScope,
    private val userToken: () -> String?,
    private val isScrobblingEnabled: () -> Boolean,
    private val onTokenInvalid: () -> Unit,
) {
    constructor(
        listenBrainz: ListenBrainz,
        outbox: ListenBrainzOutboxDao,
        tracks: TrackDao,
        scope: CoroutineScope,
    ) : this(
        client = listenBrainz.client,
        outbox = outbox,
        tracks = tracks,
        scope = scope,
        userToken = { listenBrainz.userToken },
        isScrobblingEnabled = { listenBrainz.scrobbleSettings.value.enabled },
        onTokenInvalid = listenBrainz::onTokenInvalid,
    )

    private val mutex = Mutex()
    private var retryJob: Job? = null

    /** Listens waiting to be sent. */
    val pending: Flow<Int> = outbox.observeCount(ListenBrainzOutboxItem.SCROBBLE)

    private val active get() = userToken() != null && isScrobblingEnabled()

    /** Shows the song on the user's ListenBrainz profile as playing now. Best effort, never retried. */
    fun nowPlaying(trackId: String) {
        val token = userToken() ?: return
        if (!isScrobblingEnabled()) return
        scope.launch {
            val t = tracks.getByIds(listOf(trackId)).firstOrNull() ?: return@launch
            val artist = t.artist ?: t.albumArtist ?: return@launch
            runCatching {
                client.submitListens(
                    token = token,
                    listenType = ListenBrainzApiClient.LISTEN_TYPE_PLAYING_NOW,
                    submissions = listOf(
                        ListenSubmission(
                            artist = artist,
                            track = t.title,
                            album = t.album,
                            durationMs = t.durationMs.takeIf { it > 0 },
                            trackNumber = t.trackNumber,
                            mbRecordingId = t.mbRecordingId,
                            mbReleaseId = t.mbReleaseId,
                            timestampSec = null,
                        )
                    ),
                )
            }.onFailure { Log.i(TAG, "Now playing not sent: $it") }
        }
    }

    /** Queues a listen of [trackId], played from [startedAtMs], and sends what's pending. */
    fun scrobble(trackId: String, startedAtMs: Long) {
        if (!active) return
        scope.launch {
            val t = tracks.getByIds(listOf(trackId)).firstOrNull() ?: return@launch
            val artist = t.artist ?: t.albumArtist ?: return@launch
            outbox.insert(
                ListenBrainzOutboxItem(
                    action = ListenBrainzOutboxItem.SCROBBLE,
                    artist = artist,
                    track = t.title,
                    album = t.album,
                    albumArtist = t.albumArtist,
                    durationMs = t.durationMs.takeIf { it > 0 },
                    trackNumber = t.trackNumber,
                    mbRecordingId = t.mbRecordingId,
                    mbReleaseId = t.mbReleaseId,
                    timestamp = startedAtMs / 1000,
                )
            )
            send()
        }
    }

    /** Loves (or unloves) [trackId] on ListenBrainz when added to (or removed from) favourites. */
    fun love(trackId: String, loved: Boolean) {
        if (userToken() == null) return
        scope.launch {
            val t = tracks.getByIds(listOf(trackId)).firstOrNull() ?: return@launch
            val artist = t.artist ?: t.albumArtist ?: return@launch
            outbox.insert(
                ListenBrainzOutboxItem(
                    action = if (loved) ListenBrainzOutboxItem.LOVE else ListenBrainzOutboxItem.UNLOVE,
                    artist = artist,
                    track = t.title,
                    album = t.album,
                    albumArtist = t.albumArtist,
                    durationMs = t.durationMs.takeIf { it > 0 },
                    trackNumber = t.trackNumber,
                    mbRecordingId = t.mbRecordingId,
                    mbReleaseId = t.mbReleaseId,
                    timestamp = System.currentTimeMillis() / 1000,
                )
            )
            send()
        }
    }

    /** Sends whatever is waiting (at app start, after connecting, on retry). */
    fun flush() {
        scope.launch { send() }
    }

    /** Sends what's waiting and returns when done (or when it has to wait for ListenBrainz). */
    suspend fun send() {
        sendScrobbles()
        sendLoves()
    }

    private suspend fun sendScrobbles() = mutex.withLock {
        while (true) {
            val token = userToken() ?: return@withLock
            val batch = outbox.pending(ListenBrainzOutboxItem.SCROBBLE, BATCH)
            if (batch.isEmpty()) return@withLock
            try {
                val listenType = if (batch.size == 1) {
                    ListenBrainzApiClient.LISTEN_TYPE_SINGLE
                } else {
                    ListenBrainzApiClient.LISTEN_TYPE_IMPORT
                }
                val submissions = batch.map { item ->
                    ListenSubmission(
                        artist = item.artist,
                        track = item.track,
                        album = item.album,
                        durationMs = item.durationMs,
                        trackNumber = item.trackNumber,
                        mbRecordingId = item.mbRecordingId,
                        mbReleaseId = item.mbReleaseId,
                        timestampSec = item.timestamp,
                    )
                }
                client.submitListens(token, listenType, submissions)
                Log.i(TAG, "Submitted ${batch.size} listen(s) to ListenBrainz")
                outbox.delete(batch.map { it.id })
            } catch (e: ListenBrainzException) {
                when {
                    e.isAuthError -> {
                        onTokenInvalid()
                        return@withLock
                    }
                    e.isTemporary -> {
                        retryLater(e.retryAfterSec)
                        return@withLock
                    }
                    else -> {
                        Log.w(TAG, "ListenBrainz refused ${batch.size} listens; dropping them", e)
                        outbox.delete(batch.map { it.id })
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "ListenBrainz submission postponed: $e")
                retryLater()
                return@withLock
            }
        }
    }

    private suspend fun sendLoves() = mutex.withLock {
        while (true) {
            val token = userToken() ?: return@withLock
            val item = outbox.pendingLoves(1).firstOrNull() ?: return@withLock
            try {
                val mbid = ListenBrainzClient.validMbidOrNull(item.mbRecordingId)
                    ?: resolveRecordingMbid(item, token)
                if (mbid == null) {
                    Log.i(TAG, "ListenBrainz couldn't map ${item.artist} - ${item.track} to a recording MBID; skipping ${item.action}")
                    outbox.delete(listOf(item.id))
                    continue
                }
                val score = if (item.action == ListenBrainzOutboxItem.LOVE) {
                    ListenBrainzApiClient.SCORE_LOVE
                } else {
                    ListenBrainzApiClient.SCORE_REMOVE
                }
                client.submitFeedback(token = token, recordingMbid = mbid, score = score)
                outbox.delete(listOf(item.id))
            } catch (e: ListenBrainzException) {
                when {
                    e.isAuthError -> {
                        onTokenInvalid()
                        return@withLock
                    }
                    e.isTemporary -> {
                        retryLater(e.retryAfterSec)
                        return@withLock
                    }
                    else -> {
                        Log.w(TAG, "ListenBrainz refused ${item.action} of ${item.artist} - ${item.track}; dropping it", e)
                        outbox.delete(listOf(item.id))
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "ListenBrainz feedback postponed: $e")
                retryLater()
                return@withLock
            }
        }
    }

    private suspend fun resolveRecordingMbid(item: ListenBrainzOutboxItem, token: String): String? {
        val withAlbum = if (!item.album.isNullOrBlank()) {
            client.lookupMetadata(item.artist, item.track, item.album, token)?.recordingMbid
        } else {
            null
        }
        return withAlbum ?: client.lookupMetadata(item.artist, item.track, null, token)?.recordingMbid
    }

    private fun retryLater(retryAfterSec: Int? = null) {
        if (retryJob?.isActive == true) return
        val delayMs = retryAfterSec?.let { ((it + 1).coerceAtLeast(1) * 1000L).coerceAtMost(RETRY_MS) } ?: RETRY_MS
        retryJob = scope.launch {
            delay(delayMs)
            send()
        }
    }

    private companion object {
        const val TAG = "ListenBrainzScrobbler"
        const val BATCH = 50
        const val RETRY_MS = 5 * 60_000L
    }
}
