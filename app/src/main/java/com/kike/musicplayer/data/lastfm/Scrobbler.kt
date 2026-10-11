package com.kike.musicplayer.data.lastfm

import android.util.Log
import com.kike.musicplayer.data.db.LastFmOutboxDao
import com.kike.musicplayer.data.db.LastFmOutboxItem
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
 * Sends plays, loves and unloves to Last.fm. They go through an outbox table first, so they survive
 * being offline and app restarts; scrobbles are sent oldest first in batches of up to 50, as
 * Last.fm asks (https://www.last.fm/api/scrobbling), loves one by one in the order they were made.
 */
class Scrobbler(
    private val lastFm: LastFm,
    private val outbox: LastFmOutboxDao,
    private val tracks: TrackDao,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var retryJob: Job? = null

    /** Scrobbles waiting to be sent. */
    val pending: Flow<Int> = outbox.observeCount(LastFmOutboxItem.SCROBBLE)

    private val active get() = lastFm.sessionKey != null && lastFm.scrobbleSettings.value.enabled

    /** Shows the song on the user's Last.fm profile as playing now. Best effort, never retried. */
    fun nowPlaying(trackId: String) {
        val sk = lastFm.sessionKey ?: return
        if (!lastFm.scrobbleSettings.value.enabled) return
        scope.launch {
            val t = tracks.getByIds(listOf(trackId)).firstOrNull() ?: return@launch
            runCatching {
                lastFm.client.call(
                    "track.updateNowPlaying",
                    buildMap {
                        put("artist", t.artist ?: return@launch)
                        put("track", t.title)
                        t.album?.let { put("album", it) }
                        t.albumArtist?.let { put("albumArtist", it) }
                        if (t.durationMs > 0) put("duration", (t.durationMs / 1000).toString())
                    },
                    sessionKey = sk, post = true,
                )
            }.onFailure { Log.i(TAG, "Now playing not sent: $it") }
        }
    }

    /** Queues a scrobble of [trackId], played from [startedAtMs], and sends what's pending. */
    fun scrobble(trackId: String, startedAtMs: Long) {
        if (!active) return
        scope.launch {
            val t = tracks.getByIds(listOf(trackId)).firstOrNull() ?: return@launch
            val artist = t.artist ?: return@launch // Last.fm needs an artist
            outbox.insert(
                LastFmOutboxItem(
                    action = LastFmOutboxItem.SCROBBLE,
                    artist = artist,
                    track = t.title,
                    album = t.album,
                    albumArtist = t.albumArtist,
                    durationSec = (t.durationMs / 1000).toInt().takeIf { it > 0 },
                    timestamp = startedAtMs / 1000,
                )
            )
            send()
        }
    }

    /** Loves (or unloves) [trackId] on Last.fm: a song was added to (removed from) the favourites. */
    fun love(trackId: String, loved: Boolean) {
        if (lastFm.sessionKey == null) return
        scope.launch {
            val t = tracks.getByIds(listOf(trackId)).firstOrNull() ?: return@launch
            outbox.insert(
                LastFmOutboxItem(
                    action = if (loved) LastFmOutboxItem.LOVE else LastFmOutboxItem.UNLOVE,
                    artist = t.artist ?: t.albumArtist ?: return@launch,
                    track = t.title,
                    album = t.album,
                    albumArtist = t.albumArtist,
                    durationSec = null,
                    timestamp = System.currentTimeMillis() / 1000,
                )
            )
            send()
        }
    }

    /** Sends whatever is waiting (at app start, after reconnecting, on retry). */
    fun flush() {
        scope.launch { send() }
    }

    /** Sends what's waiting and returns when done (or when it has to wait for Last.fm). */
    suspend fun send() {
        sendScrobbles()
        sendLoves()
    }

    private suspend fun sendLoves() = mutex.withLock {
        while (true) {
            val sk = lastFm.sessionKey ?: return@withLock
            val item = outbox.pendingLoves(1).firstOrNull() ?: return@withLock
            try {
                lastFm.client.call(
                    if (item.action == LastFmOutboxItem.LOVE) "track.love" else "track.unlove",
                    mapOf("artist" to item.artist, "track" to item.track),
                    sessionKey = sk, post = true,
                )
                outbox.delete(listOf(item.id))
            } catch (e: LastFmException) {
                when {
                    e.code == LastFmException.INVALID_SESSION -> {
                        lastFm.onSessionInvalid()
                        return@withLock
                    }
                    e.isTemporary -> {
                        retryLater()
                        return@withLock
                    }
                    else -> {
                        Log.w(TAG, "Last.fm refused ${item.action} of ${item.artist} - ${item.track}; dropping it", e)
                        outbox.delete(listOf(item.id))
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Love postponed: $e")
                retryLater()
                return@withLock
            }
        }
    }

    private suspend fun sendScrobbles() = mutex.withLock {
        while (true) {
            val sk = lastFm.sessionKey ?: return@withLock
            val batch = outbox.pending(LastFmOutboxItem.SCROBBLE, BATCH)
            if (batch.isEmpty()) return@withLock
            try {
                val json = lastFm.client.call("track.scrobble", batchParams(batch), sessionKey = sk, post = true)
                val attr = runCatching { lastFm.client.parse<ScrobblesJson>(json).scrobbles.attr }.getOrNull()
                Log.i(TAG, "Scrobbled ${attr?.accepted ?: "?"} of ${batch.size} (${attr?.ignored ?: 0} ignored by Last.fm)")
                outbox.delete(batch.map { it.id })
            } catch (e: LastFmException) {
                when {
                    e.code == LastFmException.INVALID_SESSION -> {
                        // Kept for when the user connects again.
                        lastFm.onSessionInvalid()
                        return@withLock
                    }
                    e.isTemporary -> {
                        retryLater()
                        return@withLock
                    }
                    else -> {
                        // A malformed request won't succeed later either (Last.fm: don't retry).
                        Log.w(TAG, "Last.fm refused ${batch.size} scrobbles; dropping them", e)
                        outbox.delete(batch.map { it.id })
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Scrobbling postponed: $e")
                retryLater()
                return@withLock
            }
        }
    }

    private fun retryLater() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_MS)
            send()
        }
    }

    private fun batchParams(batch: List<LastFmOutboxItem>) = buildMap {
        batch.forEachIndexed { i, s ->
            put("artist[$i]", s.artist)
            put("track[$i]", s.track)
            put("timestamp[$i]", s.timestamp.toString())
            s.album?.let { put("album[$i]", it) }
            s.albumArtist?.let { put("albumArtist[$i]", it) }
            s.durationSec?.let { put("duration[$i]", it.toString()) }
        }
    }

    private class ScrobblesJson(val scrobbles: Scrobbles)
    private class Scrobbles(@com.squareup.moshi.Json(name = "@attr") val attr: Attr?)
    private class Attr(val accepted: Int?, val ignored: Int?)

    private companion object {
        const val TAG = "Scrobbler"
        const val BATCH = 50
        const val RETRY_MS = 5 * 60_000L
    }
}
