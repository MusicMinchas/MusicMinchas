package com.kike.musicplayer.data.listenbrainz

import android.util.Log
import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.FavouriteDao
import com.kike.musicplayer.data.db.HistoryDao
import com.kike.musicplayer.data.db.LastFmScrobbleDao
import com.kike.musicplayer.data.db.LastFmScrobbleKey
import com.kike.musicplayer.data.db.ListenBrainzListen
import com.kike.musicplayer.data.db.ListenBrainzListenDao
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.data.lastfm.ImportStatus
import com.kike.musicplayer.data.lastfm.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Brings the user's ListenBrainz history and loved recording feedback into the app.
 *
 * Safe for both brand-new ListenBrainz-only users and users who migrated their Last.fm history:
 * - Supports multiple listens sharing the same minute-truncated `uts` (via composite PK `(user, uts, msid)`).
 * - Cross-deduplicates against `lastfm_scrobbles` by exact second AND same-minute `(uts / 60, artist, track)`
 *   so minute-truncated Last.fm -> ListenBrainz imports and CSV-aligned `play_history` rows never duplicate plays.
 * - Deduplicates new listens against `play_history` per `(trackId, second)` so distinct songs sharing a
 *   minute-truncated timestamp for ListenBrainz-only users are all preserved.
 */
class ListenBrainzImporter(
    private val client: ListenBrainzApiClient,
    private val listens: ListenBrainzListenDao,
    private val lastFmScrobbles: LastFmScrobbleDao,
    private val history: HistoryDao,
    private val tracks: TrackDao,
    private val favourites: FavouriteDao,
    private val flushOutbox: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val currentUser: () -> String?,
    private val currentToken: () -> String?,
    private val getImportedTotal: () -> Int,
    private val setImportedTotal: (Int) -> Unit,
    private val requestIntervalMs: Long = REQUEST_INTERVAL_MS,
) {
    constructor(
        listenBrainz: ListenBrainz,
        listens: ListenBrainzListenDao,
        lastFmScrobbles: LastFmScrobbleDao,
        history: HistoryDao,
        tracks: TrackDao,
        favourites: FavouriteDao,
        scrobbler: ListenBrainzScrobbler,
        scope: CoroutineScope,
    ) : this(
        client = listenBrainz.client,
        listens = listens,
        lastFmScrobbles = lastFmScrobbles,
        history = history,
        tracks = tracks,
        favourites = favourites,
        flushOutbox = scrobbler::send,
        scope = scope,
        currentUser = { listenBrainz.state.value.user },
        currentToken = { listenBrainz.userToken },
        getImportedTotal = { listenBrainz.importedTotal },
        setImportedTotal = { listenBrainz.importedTotal = it },
    )

    private val mutex = Mutex()

    private val _status = MutableStateFlow(ImportStatus())
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    fun counts(user: String) = listens.observeCounts(user)

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
     * Downloads listens (all of them when [everything], else only those newer than the last import).
     * Returns the running import (null when not connected or one is already running).
     */
    fun import(everything: Boolean): Job? {
        val user = currentUser() ?: return null
        if (mutex.isLocked) return null
        return scope.launch {
            mutex.withLock {
                _status.value = ImportStatus(running = true, text = "Connecting to ListenBrainz…")
                val result = runCatching {
                    val token = currentToken()
                    val latest = if (everything) null else listens.latest(user)
                    val total = withRetries { client.getListenCount(user, token) }
                    var downloaded = download(user, token, latest)
                    val prevTotal = getImportedTotal()
                    if (latest != null && (prevTotal < 0 || total > prevTotal + downloaded.size)) {
                        downloaded = downloaded + download(user, token, null)
                    }
                    setImportedTotal(total)
                    _status.value = ImportStatus(running = true, text = "Matching with your library…")
                    val added = addPlays()
                    val waiting = listens.unmatched().mapTo(HashSet()) { it.uts to it.msid }
                    val plays = downloaded.count { it in added }
                    val missing = downloaded.count { it in waiting }
                    Triple(downloaded.size, plays, missing)
                }
                _status.value = result.fold(
                    onSuccess = { (downloaded, plays, missing) ->
                        val already = (downloaded - plays - missing).coerceAtLeast(0)
                        val parts = listOfNotNull(
                            count(plays, "added as a play", "added as plays").takeIf { plays > 0 },
                            "$already already in your plays".takeIf { already > 0 },
                            "$missing not in your library".takeIf { missing > 0 },
                        )
                        ImportStatus(
                            text = when (downloaded) {
                                0 -> "No new listens"
                                else -> "${count(downloaded, "new listen")} from ListenBrainz: ${parts.joinToString(", ")}"
                            }
                        )
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
     * Adds the user's ListenBrainz loved tracks (score = 1) that are in the library to favourites,
     * resolving missing metadata via `/1/metadata/recording/` when possible and deduplicating
     * against both exact track IDs and cross-source song keys.
     */
    fun importLoved(): Job? {
        val user = currentUser() ?: return null
        if (mutex.isLocked) return null
        return scope.launch {
            mutex.withLock {
                _status.value = ImportStatus(running = true, text = "Downloading loved tracks…", loved = true)
                val result = runCatching {
                    flushOutbox()
                    val token = currentToken()
                    val rawLoved = downloadLoved(user, token)
                    val missingMbids = rawLoved.mapNotNull { item ->
                        item.mbid?.takeIf { item.artist.isNullOrBlank() || item.track.isNullOrBlank() }
                    }.distinct()
                    val resolvedMeta = if (missingMbids.isNotEmpty()) {
                        runCatching { withRetries { client.getRecordingMetadata(missingMbids, token) } }
                            .onFailure { Log.i(TAG, "Recording metadata lookup skipped: $it") }
                            .getOrDefault(emptyMap())
                    } else {
                        emptyMap()
                    }
                    val loved = rawLoved.map { item ->
                        if (!item.artist.isNullOrBlank() && !item.track.isNullOrBlank()) item
                        else {
                            val m = item.mbid?.let { resolvedMeta[it] }
                            if (m != null) item.copy(
                                artist = item.artist ?: m.artistCreditName,
                                track = item.track ?: m.recordingName,
                                album = item.album ?: m.releaseName,
                            ) else item
                        }
                    }

                    val artists = loved.mapNotNull { it.artist }
                    val mbids = loved.mapNotNull { it.mbid }
                    val matcher = TrackMatcher.forScrobbles(tracks, artists, mbids)

                    val existingFavIds = favourites.allTrackIds().toHashSet()
                    val existingSongKeys = if (existingFavIds.isNotEmpty()) {
                        tracks.getByIds(existingFavIds).mapNotNullTo(HashSet()) { t ->
                            t.songKey.takeIf { it.isNotEmpty() }
                        }
                    } else {
                        HashSet()
                    }

                    var matchedTotal = 0
                    val toInsert = ArrayList<Favourite>()
                    for (item in loved) {
                        val track = matcher.match(
                            artist = item.artist.orEmpty(),
                            title = item.track.orEmpty(),
                            album = item.album,
                            mbid = item.mbid,
                        ) ?: continue
                        matchedTotal++
                        val key = track.songKey
                        if (track.id in existingFavIds) continue
                        if (key.isNotEmpty() && key in existingSongKeys) continue
                        existingFavIds.add(track.id)
                        if (key.isNotEmpty()) existingSongKeys.add(key)
                        toInsert.add(Favourite(track.id, item.uts * 1000))
                    }
                    val added = if (toInsert.isNotEmpty()) {
                        favourites.insertAll(toInsert).count { it != -1L }
                    } else {
                        0
                    }
                    Triple(loved.size, added, loved.size - matchedTotal)
                }
                _status.value = result.fold(
                    onSuccess = { (loved, added, missing) ->
                        ImportStatus(
                            loved = true,
                            text = when (loved) {
                                0 -> "No loved tracks on ListenBrainz"
                                else -> "${count(loved, "loved track")}: ${count(added, "new favourite")}" +
                                    if (missing > 0) ", $missing not in your library" else ""
                            }
                        )
                    },
                    onFailure = {
                        Log.w(TAG, "Loved tracks import failed", it)
                        ImportStatus(loved = true, text = "Importing loved tracks stopped: ${it.message}")
                    },
                )
            }
        }
    }

    /** Matches waiting listens against the library again (it changed). */
    fun rematch() {
        scope.launch {
            mutex.withLock {
                runCatching { addPlays().size }
                    .onSuccess { if (it > 0) Log.i(TAG, "$it ListenBrainz listens matched songs new to the library") }
                    .onFailure { Log.w(TAG, "Rematch failed", it) }
            }
        }
    }

    /**
     * Paginates backwards via `max_ts` (up to 1,000 listens/page) until reaching [afterUts] or
     * the oldest listen on ListenBrainz. Uses `oldestInPage + 1` at full-page boundaries so
     * listens sharing the boundary `uts` with distinct `msid`s are never skipped.
     */
    internal suspend fun download(user: String, token: String?, afterUts: Long?): Set<Pair<Long, String>> {
        var maxTs: Long = System.currentTimeMillis() / 1000 + 1
        var pageNum = 0
        val downloaded = HashSet<Pair<Long, String>>()
        while (true) {
            pageNum++
            _status.value = ImportStatus(
                running = true,
                text = if (pageNum > 1) "Downloading listens: page $pageNum…" else "Downloading listens…",
            )
            val page = withRetries {
                client.getListens(user = user, token = token, maxTs = maxTs, count = ListenBrainzApiClient.MAX_ITEMS_PER_GET)
            }
            if (page.listens.isEmpty()) break

            val filtered = if (afterUts != null) page.listens.filter { it.uts > afterUts } else page.listens
            if (filtered.isNotEmpty()) {
                val rows = filtered.map { r ->
                    ListenBrainzListen(
                        user = user,
                        uts = r.uts,
                        artist = r.artist,
                        track = r.track,
                        album = r.album,
                        mbid = r.mbid,
                        msid = r.msid.orEmpty(),
                    )
                }
                val ids = listens.insertAll(rows)
                rows.filterIndexed { i, _ -> ids[i] != -1L }.mapTo(downloaded) { it.uts to it.msid }
            }

            val oldestInPage = page.listens.minOf { it.uts }
            val newestInPage = page.listens.maxOf { it.uts }
            if (afterUts != null && oldestInPage <= afterUts) break
            if (page.listens.size < ListenBrainzApiClient.MAX_ITEMS_PER_GET) break

            // `max_ts` is strictly `<`: re-include `oldestInPage` when it straddles the page boundary
            // so sibling listens at that exact timestamp aren't skipped (PK `(user, uts, msid)` dedupes).
            val nextMaxTs = if (oldestInPage < newestInPage && oldestInPage + 1 < maxTs) {
                oldestInPage + 1
            } else {
                oldestInPage
            }
            if (nextMaxTs >= maxTs) break
            maxTs = nextMaxTs
            pace(page.rateLimit)
        }
        return downloaded
    }

    private suspend fun downloadLoved(user: String, token: String?): List<RemoteFeedback> {
        val loved = ArrayList<RemoteFeedback>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val page = withRetries {
                client.getFeedback(
                    user = user,
                    token = token,
                    score = ListenBrainzApiClient.SCORE_LOVE,
                    count = ListenBrainzApiClient.DEFAULT_FEEDBACK_PAGE,
                    offset = offset,
                )
            }
            total = page.totalCount
            if (page.items.isEmpty() || page.count <= 0) break
            loved.addAll(page.items)
            offset += page.count
            if (offset < total) pace(page.rateLimit)
        }
        return loved
    }

    /**
     * Matches unmatched ListenBrainz listens with the library and adds genuinely new plays.
     *
     * Cross-deduplication safeguards:
     * 1. Listens matching an existing `lastfm_scrobbles` row — either at the exact second OR
     *    within the same minute (`l.uts % 60 == 0` and same normalized artist+title, because
     *    ListenBrainz's Last.fm importer truncates bulk timestamps to `:00`) — copy `lastfm.trackId`
     *    directly without re-running `TrackMatcher` and never insert a duplicate `PlayHistory` row.
     * 2. Remaining matched listens deduplicate against `play_history` by `(trackId, second)` so
     *    distinct songs sharing a minute-truncated `uts` for ListenBrainz-only users are all kept.
     */
    internal suspend fun addPlays(): Set<Pair<Long, String>> = withContext(Dispatchers.Default) {
        val waiting = listens.unmatched()
        if (waiting.isEmpty()) return@withContext emptySet()

        val lfmKeys = lastFmScrobbles.allKeys()
        val consumedLfm = HashSet<LastFmScrobbleKey>()
        val byExactUtsSong = HashMap<Pair<Long, String>, ArrayDeque<LastFmScrobbleKey>>()
        val byExactUts = HashMap<Long, ArrayDeque<LastFmScrobbleKey>>()
        val byMinuteSong = HashMap<Pair<Long, String>, ArrayDeque<LastFmScrobbleKey>>()

        for (k in lfmKeys) {
            val norm = songNorm(k.artist, k.track)
            byExactUtsSong.getOrPut(k.uts to norm) { ArrayDeque() }.addLast(k)
            byExactUts.getOrPut(k.uts) { ArrayDeque() }.addLast(k)
            byMinuteSong.getOrPut((k.uts / 60L) to norm) { ArrayDeque() }.addLast(k)
        }

        fun takeFirstUnconsumed(queue: ArrayDeque<LastFmScrobbleKey>?): LastFmScrobbleKey? {
            if (queue == null) return null
            while (queue.isNotEmpty()) {
                val cand = queue.removeFirst()
                if (consumedLfm.add(cand)) return cand
            }
            return null
        }

        val linkedFromLastFm = ArrayList<ListenBrainzListen>()
        val remaining = ArrayList<ListenBrainzListen>()
        for (l in waiting) {
            val norm = songNorm(l.artist, l.track)
            val lfmMatch = takeFirstUnconsumed(byExactUtsSong[l.uts to norm])
                ?: (if (l.uts % 60L == 0L) takeFirstUnconsumed(byMinuteSong[(l.uts / 60L) to norm]) else null)
                ?: (if (l.uts % 60L != 0L) takeFirstUnconsumed(byExactUts[l.uts]) else null)
            if (lfmMatch != null) {
                if (lfmMatch.trackId != null) {
                    linkedFromLastFm.add(l.copy(trackId = lfmMatch.trackId))
                }
                // Even if lfmMatch.trackId is null (song not in library yet), do not double-insert;
                // ScrobbleImporter/ListenBrainzImporter will keep play_history in sync once added.
            } else {
                remaining.add(l)
            }
        }
        if (linkedFromLastFm.isNotEmpty()) {
            listens.setTrackIds(linkedFromLastFm)
        }
        if (remaining.isEmpty()) return@withContext emptySet()

        val matcher = TrackMatcher.forScrobbles(
            tracks,
            remaining.mapTo(HashSet()) { it.artist },
            remaining.mapNotNullTo(HashSet()) { it.mbid },
        )
        val durations = HashMap<String, Long>()
        val matched = remaining.mapNotNull { s ->
            matcher.match(s.artist, s.track, s.album, s.mbid)?.let { durations[it.id] = it.durationMs; s to it.id }
        }
        if (matched.isEmpty()) return@withContext emptySet()
        listens.setTrackIds(matched.map { (s, id) -> s.copy(trackId = id) })

        val takenByTrackSec = history.allTrackTimestamps()
            .mapTo(HashSet()) { it.trackId to (it.timestamp / 1000L) }
        val addedKeys = HashSet<Pair<Long, String>>()
        val plays = ArrayList<PlayHistory>()
        for ((s, id) in matched) {
            if (takenByTrackSec.add(id to s.uts)) {
                plays.add(
                    PlayHistory(
                        trackId = id,
                        playedAt = s.uts * 1000L,
                        listenedMs = durations[id]?.coerceAtLeast(0) ?: 0L,
                        counted = true,
                    )
                )
                addedKeys.add(s.uts to s.msid)
            }
        }
        if (plays.isNotEmpty()) {
            history.insertAll(plays)
        }
        addedKeys
    }

    private fun songNorm(artist: String, track: String): String =
        "${TrackMatcher.looseArtist(artist)}\u0001${TrackMatcher.looseTitle(track)}"

    private suspend fun pace(rateLimit: RateLimitInfo) {
        val delayMs = if (rateLimit.remaining != null && rateLimit.remaining <= 2 && rateLimit.resetInSec != null) {
            ((rateLimit.resetInSec + 1).coerceAtLeast(1) * 1000L).coerceAtMost(MAX_RATE_WAIT_MS)
        } else {
            requestIntervalMs
        }
        if (delayMs > 0) delay(delayMs)
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val retryable = e is IOException && (e !is ListenBrainzException || e.isTemporary)
                if (!retryable || ++attempt >= RETRIES) throw e
                val waitMs = (e as? ListenBrainzException)?.retryAfterSec
                    ?.let { ((it + 1).coerceAtLeast(1) * 1000L).coerceAtMost(MAX_RATE_WAIT_MS) }
                    ?: (RETRY_DELAY_MS * attempt)
                delay(waitMs)
            }
        }
    }

    private fun count(n: Int, noun: String, plural: String = "${noun}s") = if (n == 1) "1 $noun" else "$n $plural"

    private companion object {
        const val TAG = "ListenBrainzImporter"
        const val WAIT_MS = 500L
        const val REQUEST_INTERVAL_MS = 350L
        const val MAX_RATE_WAIT_MS = 30_000L
        const val RETRIES = 4
        const val RETRY_DELAY_MS = 3_000L
    }
}
