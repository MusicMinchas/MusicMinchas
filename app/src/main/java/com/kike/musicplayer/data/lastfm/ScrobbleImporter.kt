package com.kike.musicplayer.data.lastfm

import android.util.Log
import com.kike.musicplayer.data.db.HistoryDao
import com.kike.musicplayer.data.db.LastFmScrobble
import com.kike.musicplayer.data.db.LastFmScrobbleDao
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the import shows in the settings. */
data class ImportStatus(val running: Boolean = false, val text: String? = null)

/**
 * Brings the user's Last.fm history into the app. Every scrobble is kept in its own table, one per
 * timestamp; those whose song is in the library also become plays (Replay, Most Played, mixes),
 * unless a play already exists in that same second, which is how plays this app scrobbled itself
 * are recognised. Scrobbles of songs not in the library wait there and are matched again whenever
 * the library changes.
 */
class ScrobbleImporter(
    private val lastFm: LastFm,
    private val scrobbles: LastFmScrobbleDao,
    private val history: HistoryDao,
    private val tracks: TrackDao,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _status = MutableStateFlow(ImportStatus())
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    fun counts(user: String) = scrobbles.observeCounts(user)

    /** Downloads scrobbles (all of them when [everything], else only those newer than the last import). */
    fun import(everything: Boolean) {
        val user = lastFm.state.value.user ?: return
        if (mutex.isLocked) return
        scope.launch {
            mutex.withLock {
                _status.value = ImportStatus(running = true, text = "Connecting to Last.fm…")
                val result = runCatching {
                    val downloaded = download(user, if (everything) null else scrobbles.latest(user))
                    _status.value = ImportStatus(running = true, text = "Matching with your library…")
                    downloaded to addPlays()
                }
                _status.value = result.fold(
                    onSuccess = { (downloaded, added) ->
                        ImportStatus(text = when (downloaded) {
                            0 -> "No new scrobbles"
                            else -> "${count(downloaded, "scrobble")} downloaded, ${count(added, "play")} added"
                        })
                    },
                    onFailure = {
                        Log.w(TAG, "Import failed", it)
                        ImportStatus(text = "Import stopped: ${it.message}. What was downloaded is kept; import again to continue.")
                    },
                )
            }
        }
    }

    /** Matches waiting scrobbles against the library again (it changed). */
    fun rematch() {
        scope.launch {
            mutex.withLock {
                runCatching { addPlays() }
                    .onSuccess { if (it > 0) Log.i(TAG, "$it scrobbles matched songs new to the library") }
                    .onFailure { Log.w(TAG, "Rematch failed", it) }
            }
        }
    }

    /** Fetches the user's scrobbles after [afterUts] (all when null), newest first, 200 a page. */
    private suspend fun download(user: String, afterUts: Long?): Int {
        // Fixed end, so scrobbles arriving meanwhile don't shift the pages.
        val until = System.currentTimeMillis() / 1000
        var page = 1
        var totalPages = 1
        var downloaded = 0
        while (page <= totalPages) {
            _status.value = ImportStatus(
                running = true,
                text = if (totalPages > 1) "Downloading scrobbles: page $page of $totalPages" else "Downloading scrobbles…",
            )
            val params = buildMap {
                put("user", user)
                put("limit", PAGE_SIZE.toString())
                put("page", page.toString())
                put("to", until.toString())
                afterUts?.let { put("from", (it + 1).toString()) }
            }
            val json = withRetries { lastFm.client.call("user.getRecentTracks", params) }
            val (rows, pages) = parseRecentTracks(json, user)
            totalPages = pages
            scrobbles.insertAll(rows)
            downloaded += rows.size
            page++
            delay(REQUEST_INTERVAL_MS) // Last.fm: no more than ~5 requests a second
        }
        return downloaded
    }

    /** Matches unmatched scrobbles with the library and adds the new plays; returns how many. */
    private suspend fun addPlays(): Int = withContext(Dispatchers.Default) {
        val waiting = scrobbles.unmatched()
        if (waiting.isEmpty()) return@withContext 0
        val library = tracks.getAll()
        val matcher = TrackMatcher(library)
        val durations = library.associate { it.id to it.durationMs }
        val matched = waiting.mapNotNull { s -> matcher.match(s.artist, s.track, s.album, s.mbid)?.let { s to it.id } }
        if (matched.isEmpty()) return@withContext 0
        scrobbles.setTrackIds(matched.map { (s, id) -> s.copy(trackId = id) })
        // One play per second: a play already there (e.g. this app's own, scrobbled from here) wins.
        val taken = history.allPlayedAt().mapTo(HashSet()) { it / 1000 }
        val plays = matched.filter { (s, _) -> taken.add(s.uts) }.map { (s, id) ->
            PlayHistory(trackId = id, playedAt = s.uts * 1000, listenedMs = durations[id]?.coerceAtLeast(0) ?: 0, counted = true)
        }
        history.insertAll(plays)
        plays.size
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val retryable = e is IOException && (e !is LastFmException || e.isTemporary)
                if (!retryable || ++attempt >= RETRIES) throw e
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    /** Last.fm's JSON: recenttracks.track is a list (or a single object), with "now playing" first. */
    private fun parseRecentTracks(json: String, user: String): Pair<List<LastFmScrobble>, Int> {
        val root = lastFm.client.moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
        val recent = root["recenttracks"] as? Map<*, *> ?: throw IOException("Unexpected Last.fm response")
        val attr = recent["@attr"] as? Map<*, *>
        val pages = (attr?.get("totalPages") as? String)?.toIntOrNull() ?: 1
        val items = when (val t = recent["track"]) {
            is List<*> -> t
            is Map<*, *> -> listOf(t)
            else -> emptyList()
        }
        val rows = items.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            if ((m["@attr"] as? Map<*, *>)?.get("nowplaying") == "true") return@mapNotNull null
            val uts = ((m["date"] as? Map<*, *>)?.get("uts") as? String)?.toLongOrNull() ?: return@mapNotNull null
            fun text(field: String) = ((m[field] as? Map<*, *>)?.get("#text") as? String)?.takeIf { it.isNotBlank() }
            LastFmScrobble(
                user = user,
                uts = uts,
                artist = text("artist") ?: return@mapNotNull null,
                track = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                album = text("album"),
                mbid = (m["mbid"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
        return rows to pages
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private companion object {
        const val TAG = "ScrobbleImporter"
        const val PAGE_SIZE = 200
        const val REQUEST_INTERVAL_MS = 250L
        const val RETRIES = 4
        const val RETRY_DELAY_MS = 3_000L
    }
}
