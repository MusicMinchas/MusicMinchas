package com.kike.musicplayer.data.lastfm

import android.util.Log
import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.FavouriteDao
import com.kike.musicplayer.data.db.HistoryDao
import com.kike.musicplayer.data.db.LastFmScrobble
import com.kike.musicplayer.data.db.LastFmScrobbleDao
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** What the import shows in the settings; [loved] tells which section it belongs to. */
data class ImportStatus(val running: Boolean = false, val text: String? = null, val loved: Boolean = false)

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
    private val favourites: FavouriteDao,
    private val scrobbler: Scrobbler,
    private val scope: CoroutineScope,
    private val historicalDates: com.kike.musicplayer.data.history.HistoricalDatesRegistry? = null,
) {
    private val mutex = Mutex()

    private val _status = MutableStateFlow(ImportStatus())
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    fun counts(user: String) = scrobbles.observeCounts(user)

    /** [import], after whatever import or rematch is running, returning when done (the daily sync). */
    suspend fun importAndWait(everything: Boolean) {
        while (mutex.isLocked) delay(WAIT_MS)
        import(everything)?.join()
    }

    /** [importLoved], after whatever is running, returning when done. */
    suspend fun importLovedAndWait() {
        while (mutex.isLocked) delay(WAIT_MS)
        importLoved()?.join()
    }

    /**
     * Downloads scrobbles (all of them when [everything], else only those newer than the last import).
     * Returns the running import (null when not connected or one is already running).
     */
    fun import(everything: Boolean): Job? {
        val user = lastFm.state.value.user ?: return null
        if (mutex.isLocked) return null
        return scope.launch {
            mutex.withLock {
                _status.value = ImportStatus(running = true, text = "Connecting to Last.fm…")
                val result = runCatching {
                    val latest = if (everything) null else scrobbles.latest(user)
                    // Taken first: a scrobble arriving during the download then can't look like a backfill.
                    val total = totalOnLastFm(user)
                    var downloaded = download(user, latest)
                    // Last.fm grew by more than what came after the newest scrobble here: some were
                    // added with older dates (bulk scrobbling). Only a full pass finds those.
                    if (latest != null && (lastFm.importedTotal < 0 || total > lastFm.importedTotal + downloaded.size)) {
                        downloaded = downloaded + download(user, null)
                    }
                    lastFm.importedTotal = total
                    _status.value = ImportStatus(running = true, text = "Matching with your library…")
                    val added = addPlays()
                    val waiting = scrobbles.unmatched().mapTo(HashSet()) { it.uts }
                    val plays = downloaded.count { it in added }
                    val missing = downloaded.count { it in waiting }
                    // The rest are songs already played here (e.g. scrobbled by this app) or imported before.
                    Triple(downloaded.size, plays, missing)
                }
                _status.value = result.fold(
                    onSuccess = { (downloaded, plays, missing) ->
                        val parts = listOfNotNull(
                            count(plays, "added as a play", "added as plays").takeIf { plays > 0 },
                            "${downloaded - plays - missing} already in your plays".takeIf { downloaded - plays - missing > 0 },
                            "$missing not in your library".takeIf { missing > 0 },
                        )
                        ImportStatus(text = when (downloaded) {
                            0 -> "No new scrobbles"
                            else -> "${count(downloaded, "new scrobble")} from Last.fm: ${parts.joinToString(", ")}"
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

    /**
     * Adds the user's Last.fm loved tracks that are in the library to the favourites, dated when
     * they were loved. Loves/unloves still waiting to be sent go first, so a song just removed from
     * the favourites doesn't come straight back.
     */
    fun importLoved(): Job? {
        val user = lastFm.state.value.user ?: return null
        if (mutex.isLocked) return null
        return scope.launch {
            mutex.withLock {
                _status.value = ImportStatus(running = true, text = "Downloading loved tracks…", loved = true)
                val result = runCatching {
                    scrobbler.send()
                    val loved = downloadLoved(user)
                    val matcher = TrackMatcher.forScrobbles(tracks, loved.map { it.artist }, loved.mapNotNull { it.mbid })
                    val matches = loved.mapNotNull { l -> matcher.match(l.artist, l.track, null, l.mbid)?.let { Favourite(it.id, l.uts * 1000) } }
                    // Two loves can be the same library song (e.g. loved under two spellings): one favourite.
                    val added = favourites.insertAll(matches.distinctBy { it.trackId }).count { it != -1L }
                    Triple(loved.size, added, loved.size - matches.size)
                }
                _status.value = result.fold(
                    onSuccess = { (loved, added, missing) ->
                        ImportStatus(loved = true, text = when (loved) {
                            0 -> "No loved tracks on Last.fm"
                            else -> "${count(loved, "loved track")}: ${count(added, "new favourite")}" +
                                if (missing > 0) ", $missing not in your library" else ""
                        })
                    },
                    onFailure = {
                        Log.w(TAG, "Loved tracks import failed", it)
                        ImportStatus(loved = true, text = "Importing loved tracks stopped: ${it.message}")
                    },
                )
            }
        }
    }

    private class Loved(val uts: Long, val artist: String, val track: String, val mbid: String?)

    private suspend fun downloadLoved(user: String): List<Loved> {
        val loved = mutableListOf<Loved>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val params = mapOf("user" to user, "limit" to PAGE_SIZE.toString(), "page" to page.toString())
            val json = withRetries { lastFm.client.call("user.getLovedTracks", params) }
            val root = lastFm.client.moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
            val list = root["lovedtracks"] as? Map<*, *> ?: throw IOException("Unexpected Last.fm response")
            totalPages = ((list["@attr"] as? Map<*, *>)?.get("totalPages") as? String)?.toIntOrNull() ?: 1
            val items = when (val t = list["track"]) {
                is List<*> -> t
                is Map<*, *> -> listOf(t)
                else -> emptyList()
            }
            items.mapNotNullTo(loved) { item ->
                val m = item as? Map<*, *> ?: return@mapNotNullTo null
                Loved(
                    uts = ((m["date"] as? Map<*, *>)?.get("uts") as? String)?.toLongOrNull() ?: (System.currentTimeMillis() / 1000),
                    artist = ((m["artist"] as? Map<*, *>)?.get("name") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNullTo null,
                    track = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNullTo null,
                    mbid = (m["mbid"] as? String)?.takeIf { it.isNotBlank() },
                )
            }
            page++
            delay(REQUEST_INTERVAL_MS)
        }
        return loved
    }

    /** Matches waiting scrobbles against the library again (it changed). */
    fun rematch() {
        scope.launch {
            mutex.withLock {
                runCatching { addPlays().size }
                    .onSuccess { if (it > 0) Log.i(TAG, "$it scrobbles matched songs new to the library") }
                    .onFailure { Log.w(TAG, "Rematch failed", it) }
            }
        }
    }

    /** The number of scrobbles on the user's Last.fm profile. */
    private suspend fun totalOnLastFm(user: String): Int {
        val json = withRetries { lastFm.client.call("user.getRecentTracks", mapOf("user" to user, "limit" to "1")) }
        val root = lastFm.client.moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
        val attr = (root["recenttracks"] as? Map<*, *>)?.get("@attr") as? Map<*, *>
        return (attr?.get("total") as? String)?.toIntOrNull() ?: throw IOException("Unexpected Last.fm response")
    }

    /**
     * Fetches the user's scrobbles after [afterUts] (all when null), newest first, 200 a page.
     * Returns the timestamps of those not stored before.
     */
    private suspend fun download(user: String, afterUts: Long?): Set<Long> {
        // Fixed end, so scrobbles arriving meanwhile don't shift the pages.
        val until = System.currentTimeMillis() / 1000
        var page = 1
        var totalPages = 1
        val downloaded = HashSet<Long>()
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
            val ids = scrobbles.insertAll(rows)
            rows.filterIndexed { i, _ -> ids[i] != -1L }.mapTo(downloaded) { it.uts }
            page++
            delay(REQUEST_INTERVAL_MS) // Last.fm: no more than ~5 requests a second
        }
        return downloaded
    }

    /** Matches unmatched scrobbles with the library and adds the new plays; returns their timestamps. */
    private suspend fun addPlays(): Set<Long> = withContext(Dispatchers.Default) {
        val waiting = scrobbles.unmatched()
        if (waiting.isEmpty()) return@withContext emptySet()
        val matcher = TrackMatcher.forScrobbles(tracks, waiting.mapTo(HashSet()) { it.artist }, waiting.mapNotNullTo(HashSet()) { it.mbid })
        val durations = HashMap<String, Long>()
        val matched = waiting.mapNotNull { s ->
            matcher.match(s.artist, s.track, s.album, s.mbid)?.let { durations[it.id] = it.durationMs; s to it.id }
        }
        if (matched.isEmpty()) return@withContext emptySet()
        scrobbles.setTrackIds(matched.map { (s, id) -> s.copy(trackId = id) })
        // One play per second: a play already there (e.g. this app's own, scrobbled from here) wins.
        val taken = history.allPlayedAt().mapTo(HashSet()) { it / 1000 }
        val plays = matched.filter { (s, _) -> taken.add(s.uts) }.map { (s, id) ->
            PlayHistory(trackId = id, playedAt = s.uts * 1000, listenedMs = durations[id]?.coerceAtLeast(0) ?: 0, counted = true)
        }
        history.insertAll(plays)
        val dates = historicalDates?.csvDates?.value.orEmpty()
        if (dates.isNotEmpty()) {
            val newlyAddedTrackIds = plays.mapTo(HashSet()) { it.trackId }.filter { it in dates }
            if (newlyAddedTrackIds.isNotEmpty()) {
                val targets = HashMap<String, Long>()
                val targetPlays = HashMap<String, List<Long>>()
                for (tid in newlyAddedTrackIds) {
                    val ts = dates[tid] ?: continue
                    targets[tid] = ts
                    targetPlays[tid] = listOf(ts)
                }
                if (targets.isNotEmpty()) {
                    com.kike.musicplayer.data.history.HistoricalPlayImporter.alignPlayHistoryTimestamps(history, targetPlays, targets)
                }
            }
        }
        plays.mapTo(HashSet()) { it.playedAt / 1000 }
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

    private fun count(n: Int, noun: String, plural: String = "${noun}s") = if (n == 1) "1 $noun" else "$n $plural"

    private companion object {
        const val TAG = "ScrobbleImporter"
        const val PAGE_SIZE = 200
        const val WAIT_MS = 500L
        const val REQUEST_INTERVAL_MS = 250L
        const val RETRIES = 4
        const val RETRY_DELAY_MS = 3_000L
    }
}
