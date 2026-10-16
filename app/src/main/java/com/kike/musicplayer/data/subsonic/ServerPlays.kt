package com.kike.musicplayer.data.subsonic

import android.util.Log
import com.kike.musicplayer.data.SearchText
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.ServerOutboxItem
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.lastfm.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Reports plays made in the app to the Subsonic server (scrobble with submission), so its play
 * counts and "recently played" include them; as the user chooses: plays of server songs, and/or
 * plays of device and Drive songs the server also has (same artist and title). Plays imported from
 * Last.fm are never reported (the server counted its own already). Reports wait in a queue
 * (server_outbox) until the server has them, so plays offline or while it's down aren't lost.
 */
class ServerPlays(
    private val account: SubsonicAccount,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val outbox = db.serverOutbox()
    private val mutex = Mutex()

    val pending = outbox.observeCount()

    /** A play of [trackId] that started at [startedAtMs] has counted (half the song listened). */
    fun played(trackId: String, startedAtMs: Long) {
        val reportServer = account.reportServerPlays.value
        val reportOther = account.reportOtherPlays.value
        if (!reportServer && !reportOther) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val track = db.tracks().get(trackId) ?: return@launch
                val songId = when {
                    track.source == Track.SOURCE_SUBSONIC -> if (reportServer) track.uri.removePrefix(SubsonicUrls.PREFIX) else null
                    reportOther -> serverCopy(track)?.uri?.removePrefix(SubsonicUrls.PREFIX)
                    else -> null
                } ?: return@launch
                outbox.insert(ServerOutboxItem(songId = songId, time = startedAtMs))
                flush()
            }.onFailure { Log.w(TAG, "Couldn't queue a play for the server", it) }
        }
    }

    /** The server's copy of a device or Drive song: same artist and title (the same album preferred). */
    private suspend fun serverCopy(track: Track): Track? {
        val artist = track.artist ?: track.albumArtist ?: return null
        val match = SearchText.match("$artist ${track.title}") ?: return null
        val wantedArtists = listOfNotNull(track.artist, track.albumArtist).mapTo(HashSet(), TrackMatcher::basic)
        val wantedTitle = TrackMatcher.basic(track.title)
        val candidates = db.browse().searchSongs(match, CANDIDATES, listOf(Track.SCOPE_SERVER)).filter { c ->
            TrackMatcher.basic(c.title) == wantedTitle &&
                listOfNotNull(c.artist, c.albumArtist).any { TrackMatcher.basic(it) in wantedArtists }
        }
        val album = track.album?.let(TrackMatcher::basic)
        return candidates.firstOrNull { album != null && it.album?.let(TrackMatcher::basic) == album } ?: candidates.firstOrNull()
    }

    /** Sends what's queued, oldest first; stops at the first failure and tries again with the next play or start. */
    fun flush() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val login = account.login() ?: return@withLock
                while (true) {
                    val batch = outbox.pending(BATCH)
                    if (batch.isEmpty()) break
                    for (item in batch) {
                        try {
                            account.client.call(
                                login, "scrobble", mapOf("id" to item.songId, "time" to item.time.toString(), "submission" to "true"),
                            )
                            outbox.delete(item.id)
                        } catch (e: SubsonicException) {
                            if (e.code in SIGN_IN_ERRORS) {
                                Log.i(TAG, "Not signed in to the server, plays stay queued: ${e.message}")
                                return@withLock
                            }
                            // The server refuses this one (e.g. the song is gone): drop it, keep the rest.
                            Log.w(TAG, "Server refused a play report: ${e.message}")
                            outbox.delete(item.id)
                        } catch (e: IOException) {
                            Log.i(TAG, "Server not reachable, plays stay queued: $e")
                            return@withLock
                        }
                    }
                }
            }
        }
    }

    /** Signed out of the server: what's queued can't be sent anywhere. */
    suspend fun clear() = outbox.clear()

    private companion object {
        const val TAG = "ServerPlays"
        const val BATCH = 50
        const val CANDIDATES = 50
        val SIGN_IN_ERRORS = setOf(SubsonicException.WRONG_CREDENTIALS, SubsonicException.TOKEN_AUTH_UNSUPPORTED, SubsonicException.NOT_AUTHORIZED)
    }
}
