package com.kike.musicplayer.data.history

import android.util.Log
import com.kike.musicplayer.data.db.HistoryDao
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.byArtistNames
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.data.lastfm.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ImportStats(
    val totalRows: Int = 0,
    val matchedTracks: Int = 0,
    val matchedPlays: Int = 0,
    val skippedDuplicates: Int = 0,
    val unmatchedRows: Int = 0,
    val earliestDate: Long? = null,
    val latestDate: Long? = null,
    val topArtists: List<Pair<String, Int>> = emptyList(),
)

data class ParsedRow(
    val artist: String,
    val title: String,
    val album: String?,
    val playedAt: Long,
    val fallbackArtist: String? = null,
)

private enum class CsvFormat { UNIFIED_APPLE_MUSIC, LEGACY_APPLE_MUSIC, ITUNES }

private data class ParsedCsvInput(
    val format: CsvFormat,
    val rows: List<ParsedRow>,
    val totalDataRows: Int,
    val skippedDuplicates: Int,
    val unparseableRows: Int,
)

object HistoricalPlayImporter {
    private const val TAG = "HistoricalPlayImporter"
    private const val IN_APP_LAUNCH_MS = 1_791_417_600_000L // 2026-10-08T00:00:00Z
    private const val NOV_2026_START_MS = 1_793_491_200_000L // 2026-11-01T00:00:00Z
    private const val COLLAPSED_WINDOW_MS = 24L * 3_600_000L
    private const val FALLBACK_STEP_MS = 180_000L

    /**
     * Parses an iTunes, Legacy Apple Music, or Unified Apple Music CSV, matches entries against [tracks]
     * using [TrackMatcher], and updates [historicalStore] with authoritative last-played dates.
     *
     * If [addPlayCounts] is false (the default), NO plays are added to the play count,
     * protecting your existing playcounts and preventing inflation.
     */
    suspend fun importCsv(
        inputStream: InputStream,
        tracks: List<Track>,
        historicalStore: HistoricalDatesRegistry,
        historyDao: HistoryDao? = null,
        addPlayCounts: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): ImportStats = withContext(Dispatchers.IO) {
        val parsed = parseCsvInput(inputStream)
        if (parsed.totalDataRows == 0 && parsed.rows.isEmpty()) return@withContext ImportStats()

        val matcher = TrackMatcher(tracks)
        val siblings = buildSiblingMap(tracks)
        val priorCsvDates = historicalStore.csvDates.value
        val inAppDates = historicalStore.inAppDates.value

        val artistCounts = HashMap<String, Int>()
        val latestTrackDates = HashMap<String, Long>()
        val trackMatchedPlays = HashMap<String, ArrayList<Long>>()
        var matchedPlays = 0
        var earliestDate: Long? = null
        var latestDate: Long? = null
        var unmatchedCount = parsed.unparseableRows

        val totalParsed = parsed.rows.size.coerceAtLeast(1)
        for ((index, row) in parsed.rows.withIndex()) {
            val matched = matchRow(matcher, row)
            if (matched != null) {
                matchedPlays++
                earliestDate = earliestDate?.let { minOf(it, row.playedAt) } ?: row.playedAt
                latestDate = latestDate?.let { maxOf(it, row.playedAt) } ?: row.playedAt
                latestTrackDates[matched.id] = maxOf(latestTrackDates[matched.id] ?: 0L, row.playedAt)
                trackMatchedPlays.getOrPut(matched.id) { ArrayList() }.add(row.playedAt)
                val artistName = matched.artist ?: matched.albumArtist ?: "Unknown"
                artistCounts.merge(artistName, 1, Int::plus)
            } else {
                unmatchedCount++
            }
            if (index % 250 == 0) {
                onProgress?.invoke((index.toFloat() / totalParsed).coerceIn(0f, 0.95f))
            }
        }

        historicalStore.setCsvDates(latestTrackDates)

        if (addPlayCounts && historyDao != null) {
            val playsToInsert = latestTrackDates.map { (trackId, ts) ->
                PlayHistory(
                    trackId = trackId,
                    playedAt = ts,
                    listenedMs = 0,
                    counted = true,
                )
            }
            historyDao.insertAll(playsToInsert)
        } else if (historyDao != null && trackMatchedPlays.isNotEmpty()) {
            alignPlayHistoryTimestamps(
                historyDao = historyDao,
                trackMatchedPlays = trackMatchedPlays,
                latestTrackDates = latestTrackDates,
                inAppDates = inAppDates,
                historicalCsvDates = priorCsvDates,
                siblingTrackIds = siblings,
            )
        }

        onProgress?.invoke(1.0f)

        val sortedArtists = artistCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        ImportStats(
            totalRows = parsed.totalDataRows,
            matchedTracks = latestTrackDates.size,
            matchedPlays = matchedPlays,
            skippedDuplicates = parsed.skippedDuplicates,
            unmatchedRows = unmatchedCount,
            earliestDate = earliestDate,
            latestDate = latestDate,
            topArtists = sortedArtists,
        )
    }

    /**
     * High-performance two-tier importer:
     * 1. Matches against the user's personal owned library tracks (SAF and Google Drive).
     *    Takes ~15ms and <5MB memory without loading full server libraries up front.
     * 2. For remaining unmatched entries, loads only candidate Subsonic server tracks matching those specific artists
     *    in small chunks (50 artists at a time) to avoid CursorWindow and heap allocation issues.
     */
    suspend fun importCsvFromDao(
        inputStream: InputStream,
        trackDao: TrackDao,
        historicalStore: HistoricalDatesRegistry,
        historyDao: HistoryDao? = null,
        addPlayCounts: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): ImportStats = withContext(Dispatchers.IO) {
        onProgress?.invoke(0.05f)
        val parsed = parseCsvInput(inputStream)
        if (parsed.totalDataRows == 0 && parsed.rows.isEmpty()) return@withContext ImportStats()
        onProgress?.invoke(0.15f)

        val priorCsvDates = historicalStore.csvDates.value
        val inAppDates = historicalStore.inAppDates.value

        val artistCounts = HashMap<String, Int>()
        val latestTrackDates = HashMap<String, Long>()
        val trackMatchedPlays = HashMap<String, ArrayList<Long>>()
        var matchedPlays = 0
        var earliestDate: Long? = null
        var latestDate: Long? = null

        fun recordMatch(track: Track, playedAt: Long) {
            matchedPlays++
            earliestDate = earliestDate?.let { minOf(it, playedAt) } ?: playedAt
            latestDate = latestDate?.let { maxOf(it, playedAt) } ?: playedAt
            latestTrackDates[track.id] = maxOf(latestTrackDates[track.id] ?: 0L, playedAt)
            trackMatchedPlays.getOrPut(track.id) { ArrayList() }.add(playedAt)
            val artistName = track.artist ?: track.albumArtist ?: "Unknown"
            artistCounts.merge(artistName, 1, Int::plus)
        }

        // --- Pass 1: Match against user's OWN tracks (SAF & Google Drive) when exact on-album ---
        // Off-album / blank-album / fuzzy matches are deferred to Pass 2 so an on-album Subsonic
        // edition can beat an off-album Drive compilation (matching ScrobbleImporter semantics).
        val ownTracks = trackDao.getAll(listOf(Track.SCOPE_LOCAL, Track.SCOPE_DRIVE))
        val ownMatcher = TrackMatcher(ownTracks)
        val ownByLooseArtist = HashMap<String, MutableList<Track>>().apply {
            for (t in ownTracks) {
                for (a in listOfNotNull(t.artist, t.albumArtist).distinct()) {
                    getOrPut(TrackMatcher.looseArtist(a)) { ArrayList() }.add(t)
                }
            }
        }
        onProgress?.invoke(0.20f)

        val loadedTracksForSiblings = ArrayList<Track>(ownTracks)
        val deferredRows = ArrayList<ParsedRow>()
        val totalParsed = parsed.rows.size.coerceAtLeast(1)

        for ((index, row) in parsed.rows.withIndex()) {
            val matched = if (!row.album.isNullOrBlank()) {
                matchRow(ownMatcher, row, exactAlbumOnly = true)
            } else {
                null
            }
            if (matched != null) {
                recordMatch(matched, row.playedAt)
            } else {
                deferredRows.add(row)
            }
            if (index % 250 == 0) {
                val p = 0.20f + 0.50f * (index.toFloat() / totalParsed)
                onProgress?.invoke(p.coerceIn(0.20f, 0.70f))
            }
        }
        onProgress?.invoke(0.70f)

        // --- Pass 2: Combined Own + Subsonic matching per artist batch ---
        var remainingUnmatched = 0
        if (deferredRows.isNotEmpty()) {
            val rowsByPrimaryArtist = deferredRows.groupBy { TrackMatcher.looseArtist(it.artist) }
            val spellingsByLoose = trackDao.allArtistSpellings().groupBy { TrackMatcher.looseArtist(it) }
            val artistBatches = rowsByPrimaryArtist.keys.toList().chunked(25)

            for ((batchIndex, artistBatch) in artistBatches.withIndex()) {
                val batchRows = artistBatch.flatMap { rowsByPrimaryArtist[it].orEmpty() }
                val batchLooseArtists = HashSet<String>()
                for (row in batchRows) {
                    batchLooseArtists.add(TrackMatcher.looseArtist(row.artist))
                    row.fallbackArtist?.let { batchLooseArtists.add(TrackMatcher.looseArtist(it)) }
                }

                val batchOwn = batchLooseArtists.flatMap { ownByLooseArtist[it].orEmpty() }
                val batchSpellings = batchLooseArtists.flatMap { spellingsByLoose[it].orEmpty() }.distinct()
                val batchSubsonic = if (batchSpellings.isNotEmpty()) {
                    trackDao.byArtistNames(batchSpellings).filter { it.source == Track.SOURCE_SUBSONIC }
                } else {
                    emptyList()
                }
                if (batchSubsonic.isNotEmpty()) {
                    loadedTracksForSiblings.addAll(batchSubsonic)
                }

                val combined = (batchOwn + batchSubsonic).distinctBy { it.id }
                if (combined.isEmpty()) {
                    remainingUnmatched += batchRows.size
                } else {
                    val combinedMatcher = TrackMatcher(combined)
                    for (row in batchRows) {
                        val matched = matchRow(combinedMatcher, row)
                        if (matched != null) {
                            recordMatch(matched, row.playedAt)
                        } else {
                            remainingUnmatched++
                        }
                    }
                }

                val p = 0.70f + 0.25f * ((batchIndex + 1).toFloat() / artistBatches.size)
                onProgress?.invoke(p.coerceIn(0.70f, 0.95f))
            }
        }

        // Store authoritative dates (maxOf) without modifying play counts
        historicalStore.setCsvDates(latestTrackDates)

        // Only insert into play_history if explicitly requested
        if (addPlayCounts && historyDao != null) {
            val playsToInsert = latestTrackDates.map { (trackId, ts) ->
                PlayHistory(
                    trackId = trackId,
                    playedAt = ts,
                    listenedMs = 0,
                    counted = true,
                )
            }
            historyDao.insertAll(playsToInsert)
        } else if (historyDao != null && trackMatchedPlays.isNotEmpty()) {
            val allPlays = historyDao.allAscending()
            val loadedIds = loadedTracksForSiblings.mapTo(HashSet()) { it.id }
            val extraPlayTrackIds = allPlays.mapNotNullTo(HashSet()) { it.trackId.takeIf { id -> id !in loadedIds } }
            if (extraPlayTrackIds.isNotEmpty()) {
                loadedTracksForSiblings.addAll(trackDao.getByIds(extraPlayTrackIds))
            }
            val siblings = buildSiblingMap(loadedTracksForSiblings.distinctBy { it.id }, trackMatchedPlays.keys)
            alignPlayHistoryTimestamps(
                historyDao = historyDao,
                trackMatchedPlays = trackMatchedPlays,
                latestTrackDates = latestTrackDates,
                inAppDates = inAppDates,
                historicalCsvDates = priorCsvDates,
                siblingTrackIds = siblings,
                preloadedPlays = allPlays,
            )
        }

        onProgress?.invoke(1.0f)

        val sortedArtists = artistCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        Log.i(TAG, "Import completed: ${latestTrackDates.size} tracks matched (${matchedPlays} plays). Unmatched: ${remainingUnmatched + parsed.unparseableRows}, Duplicates: ${parsed.skippedDuplicates}")

        ImportStats(
            totalRows = parsed.totalDataRows,
            matchedTracks = latestTrackDates.size,
            matchedPlays = matchedPlays,
            skippedDuplicates = parsed.skippedDuplicates,
            unmatchedRows = remainingUnmatched + parsed.unparseableRows,
            earliestDate = earliestDate,
            latestDate = latestDate,
            topArtists = sortedArtists,
        )
    }

    /**
     * Re-aligns historical bulk-scrobbled timestamps onto real CSV play dates without adding or
     * removing any [PlayHistory] rows. Protects genuine in-app plays and avoids duplicating CSV dates
     * that were already placed onto pre-November historical rows.
     */
    suspend fun alignPlayHistoryTimestamps(
        historyDao: HistoryDao,
        trackMatchedPlays: Map<String, List<Long>>,
        latestTrackDates: Map<String, Long>,
        inAppDates: Map<String, Long> = emptyMap(),
        historicalCsvDates: Map<String, Long> = emptyMap(),
        siblingTrackIds: Map<String, Set<String>> = emptyMap(),
        preloadedPlays: List<PlayHistory>? = null,
    ) {
        val allPlays = preloadedPlays ?: historyDao.allAscending()
        if (allPlays.isEmpty()) return
        val scrobbleTimestamps = historyDao.scrobbleUtsMillis().toHashSet()

        fun isProtectedInApp(play: PlayHistory): Boolean {
            val pa = play.playedAt
            if (pa < IN_APP_LAUNCH_MS) return false
            if (pa % 1000L != 0L) return true
            if (inAppDates[play.trackId] == pa) return true
            if (pa >= NOV_2026_START_MS && pa in scrobbleTimestamps && (pa / 1000L) % 60L != 0L) return true
            if (pa !in scrobbleTimestamps && pa != historicalCsvDates[play.trackId] && play.listenedMs > 0L) return true
            return false
        }

        val playsByTrack = HashMap<String, ArrayList<PlayHistory>>()
        for (play in allPlays) {
            playsByTrack.getOrPut(play.trackId) { ArrayList() }.add(play)
        }

        val updatedValues = HashMap<Long, PlayHistory>()

        for ((trackId, rawCsvDates) in trackMatchedPlays) {
            val sortedCsvDates = rawCsvDates.distinct().sorted()
            if (sortedCsvDates.isEmpty()) continue
            val latestCsvDate = latestTrackDates[trackId] ?: sortedCsvDates.last()
            val plays = playsByTrack[trackId].orEmpty()

            // Identify pre-November non-scrobble historical plays already holding CSV timestamps (±1s tolerance
            // for epoch millisecond precision placed by earlier raw Apple Music exports).
            val consumedPlayIds = HashSet<Long>()
            val existingHistBySec = HashMap<Long, ArrayList<PlayHistory>>()
            for (play in plays) {
                val pa = play.playedAt
                if (!isProtectedInApp(play) && pa < NOV_2026_START_MS && pa !in scrobbleTimestamps) {
                    existingHistBySec.getOrPut(pa / 1000L) { ArrayList() }.add(play)
                }
            }

            val missingCsvDates = ArrayList<Long>()
            for (ts in sortedCsvDates) {
                val sec = ts / 1000L
                val existingList = existingHistBySec[sec]
                    ?: existingHistBySec[sec - 1L]
                    ?: existingHistBySec[sec + 1L]
                if (!existingList.isNullOrEmpty()) {
                    val matchedPlay = existingList.removeAt(0)
                    consumedPlayIds.add(matchedPlay.id)
                    if (existingList.isEmpty()) existingHistBySec.remove(matchedPlay.playedAt / 1000L)
                } else {
                    missingCsvDates.add(ts)
                }
            }

            // Build pool of eligible bulk plays in priority order:
            // 1. Unprotected bulk Last.fm plays still at scrobble_uts * 1000 (.000 ms).
            val pool = ArrayList<PlayHistory>()
            val poolIds = HashSet<Long>()
            fun addToPool(play: PlayHistory) {
                if (play.id !in updatedValues && poolIds.add(play.id)) {
                    pool.add(play)
                }
            }

            for (play in plays) {
                val isUnalignedScrobble = (play.playedAt in scrobbleTimestamps && play.playedAt % 1000L == 0L) ||
                        (play.playedAt >= 1789500000000L && play.listenedMs == 0L)
                if (!isProtectedInApp(play) && isUnalignedScrobble && play.id !in consumedPlayIds) {
                    addToPool(play)
                }
            }

            // 2. Rows collapsed onto a single historical_dates anchor by ScrobbleImporter during Last.fm sync.
            val hdTs = historicalCsvDates[trackId]
            if (pool.size < missingCsvDates.size && hdTs != null) {
                for (play in plays) {
                    if (play.id in consumedPlayIds || isProtectedInApp(play)) continue
                    val diff = hdTs - play.playedAt
                    if (diff in 0L..COLLAPSED_WINDOW_MS &&
                        (diff % 1000L == 0L || kotlin.math.abs((play.playedAt / 1000L) - (hdTs / 1000L)) <= 1L)
                    ) {
                        addToPool(play)
                    }
                }
            }

            // 3. Sibling edition copies still stuck in the post-November bulk scrobble cluster.
            if (pool.size < missingCsvDates.size) {
                for (sibId in siblingTrackIds[trackId].orEmpty()) {
                    if (sibId == trackId || sibId in trackMatchedPlays) continue
                    for (play in playsByTrack[sibId].orEmpty()) {
                        if (!isProtectedInApp(play) && play.playedAt >= NOV_2026_START_MS &&
                            play.playedAt in scrobbleTimestamps && play.playedAt % 1000L == 0L
                        ) {
                            addToPool(play)
                        }
                    }
                }
            }

            if (pool.isEmpty()) continue
            pool.sortBy { it.playedAt }

            if (missingCsvDates.isNotEmpty()) {
                val assignCount = minOf(pool.size, missingCsvDates.size)
                val targetDates = missingCsvDates.takeLast(assignCount)
                val poolTail = pool.takeLast(assignCount)
                val assignedPoolIds = HashSet<Long>(assignCount)

                for (i in 0 until assignCount) {
                    val play = poolTail[i]
                    val newTs = targetDates[i]
                    assignedPoolIds.add(play.id)
                    if (newTs != play.playedAt) {
                        updatedValues[play.id] = play.copy(playedAt = newTs)
                    }
                }

                // Any remaining unaligned Nov+ bulk rows on this track (e.g. duplicate upload rows or
                // mixed iTunes+AM tracks) anchor back to prior historical date / earliest CSV date.
                val anchor = hdTs?.takeIf { it > 0L } ?: missingCsvDates.first()
                val occupiedTs = targetDates.toHashSet()
                var step = if (anchor in occupiedTs) 1 else 0
                for (play in pool.asReversed()) {
                    if (play.id in assignedPoolIds) continue
                    if (play.playedAt >= NOV_2026_START_MS && play.playedAt in scrobbleTimestamps) {
                        var newTs = anchor - step * FALLBACK_STEP_MS
                        step++
                        while (newTs in occupiedTs) {
                            newTs = anchor - step * FALLBACK_STEP_MS
                            step++
                        }
                        occupiedTs.add(newTs)
                        if (newTs != play.playedAt) {
                            updatedValues[play.id] = play.copy(playedAt = newTs)
                        }
                    }
                }
            } else {
                // All CSV dates already present in DB: shift any leftover unaligned scrobble plays.
                val maxOrigPlayed = pool.last().playedAt
                val anchor = hdTs?.takeIf { it > 0L } ?: latestCsvDate
                for (play in pool) {
                    val isUnaligned = play.playedAt in scrobbleTimestamps ||
                            (play.playedAt >= 1789500000000L && play.listenedMs == 0L)
                    if (!isUnaligned) continue
                    val newTs = anchor - (maxOrigPlayed - play.playedAt)
                    if (newTs != play.playedAt) {
                        updatedValues[play.id] = play.copy(playedAt = newTs)
                    }
                }
            }
        }

        if (updatedValues.isNotEmpty()) {
            Log.i(TAG, "Aligned ${updatedValues.size} play_history timestamps with authoritative CSV dates")
            historyDao.updateAll(updatedValues.values.toList())
        }
    }

    private fun matchRow(matcher: TrackMatcher, row: ParsedRow, exactAlbumOnly: Boolean = false): Track? =
        matcher.match(row.artist, row.title, row.album, mbid = null, exactAlbumOnly = exactAlbumOnly)
            ?: row.fallbackArtist?.let { fb -> matcher.match(fb, row.title, row.album, mbid = null, exactAlbumOnly = exactAlbumOnly) }

    private fun buildSiblingMap(
        tracks: List<Track>,
        targetIds: Set<String>? = null,
    ): Map<String, Set<String>> {
        val byKey = HashMap<String, MutableSet<String>>()
        val targetKeysById = HashMap<String, MutableList<String>>()
        for (t in tracks) {
            val lt = TrackMatcher.looseTitle(t.title)
            val artists = listOfNotNull(t.artist, t.albumArtist).distinct()
            for (a in artists) {
                val key = "${TrackMatcher.looseArtist(a)}\u0001$lt"
                byKey.getOrPut(key) { HashSet() }.add(t.id)
                if (targetIds == null || t.id in targetIds) {
                    targetKeysById.getOrPut(t.id) { ArrayList(2) }.add(key)
                }
            }
        }
        val result = HashMap<String, Set<String>>(targetKeysById.size)
        for ((tid, keys) in targetKeysById) {
            val sibs = HashSet<String>()
            for (k in keys) sibs.addAll(byKey[k].orEmpty())
            if (sibs.size > 1) result[tid] = sibs
        }
        return result
    }

    internal fun isSafeArtistFallback(primary: String, fallback: String): Boolean {
        if (fallback.isBlank() || fallback == primary) return false
        val bp = TrackMatcher.basic(primary)
        val bf = TrackMatcher.basic(fallback)
        if (bf.isEmpty() || bf == bp) return false
        val cp = bp.replace(" ", "")
        val cf = bf.replace(" ", "")
        if (cf in cp || cp in cf) return true
        val tokensF = bf.split(' ').filter { it.length > 2 }
        if (tokensF.isEmpty()) return false
        val tokensP = bp.split(' ').filter { it.length > 2 }.toHashSet()
        return tokensF.all { it in tokensP }
    }

    private fun parseCsvInput(inputStream: InputStream): ParsedCsvInput {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        val lines = reader.readLines()
        if (lines.isEmpty()) return ParsedCsvInput(CsvFormat.ITUNES, emptyList(), 0, 0, 0)

        val firstLine = lines.first().trimStart('\ufeff')
        val headerCols = parseCsvLine(firstLine).map { it.trim().trim('"', '\ufeff').lowercase(Locale.US) }
        val format = when {
            "track name" in headerCols && "timestamp" in headerCols && "artist" in headerCols -> CsvFormat.UNIFIED_APPLE_MUSIC
            "track name" in headerCols && "last played date" in headerCols -> CsvFormat.LEGACY_APPLE_MUSIC
            else -> CsvFormat.ITUNES
        }

        val hasHeader = format != CsvFormat.ITUNES
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            // Unified Apple Music privacy exports are UTC; legacy iTunes CSV exports are local wall-clock time.
            timeZone = if (format == CsvFormat.UNIFIED_APPLE_MUSIC) {
                TimeZone.getTimeZone("UTC")
            } else {
                TimeZone.getDefault()
            }
        }

        var skippedDuplicates = 0
        var unparseableRows = 0
        val rows = parseRows(
            lines = lines,
            format = format,
            headerCols = headerCols,
            dateFormat = dateFormat,
            onDuplicate = { skippedDuplicates++ },
            onUnparseable = { unparseableRows++ },
        )

        val totalDataRows = (lines.size - (if (hasHeader) 1 else 0)).coerceAtLeast(0)
        return ParsedCsvInput(format, rows, totalDataRows, skippedDuplicates, unparseableRows)
    }

    private fun parseRows(
        lines: List<String>,
        format: CsvFormat,
        headerCols: List<String>,
        dateFormat: SimpleDateFormat,
        onDuplicate: () -> Unit,
        onUnparseable: () -> Unit,
    ): List<ParsedRow> {
        val dataLines = if (format != CsvFormat.ITUNES) lines.drop(1) else lines
        val seenKeys = if (format != CsvFormat.ITUNES) HashSet<String>() else null
        val result = ArrayList<ParsedRow>(dataLines.size)

        val artistIdx = headerCols.indexOf("artist").takeIf { it >= 0 } ?: 4
        val albumArtistIdx = headerCols.indexOf("album artist").takeIf { it >= 0 } ?: 0
        val trackIdx = headerCols.indexOf("track name").takeIf { it >= 0 } ?: 1
        val albumIdx = headerCols.indexOf("album name").takeIf { it >= 0 } ?: 2
        val tsIdx = headerCols.indexOf("timestamp").takeIf { it >= 0 } ?: 3

        fun parseTimestamp(raw: String): Long? = try {
            if (raw.isNotEmpty() && raw.all { it.isDigit() }) {
                raw.toLong()
            } else {
                dateFormat.parse(raw)?.time
            }
        } catch (_: Exception) {
            null
        }

        for (line in dataLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val cols = parseCsvLine(trimmed)

            when (format) {
                CsvFormat.LEGACY_APPLE_MUSIC -> {
                    if (cols.size < 2) {
                        onUnparseable()
                        continue
                    }
                    val trackName = cols[0].trim()
                    val ts = cols[1].trim().toLongOrNull()
                    if (ts == null) {
                        onUnparseable()
                        continue
                    }
                    if (seenKeys != null && !seenKeys.add("$trackName\u0001$ts")) {
                        onDuplicate()
                        continue
                    }
                    val parts = trackName.split(" - ", limit = 2)
                    if (parts.size != 2) {
                        onUnparseable()
                        continue
                    }
                    result.add(ParsedRow(parts[0].trim(), parts[1].trim(), null, ts))
                }

                CsvFormat.UNIFIED_APPLE_MUSIC -> {
                    val minSize = maxOf(artistIdx, trackIdx, tsIdx) + 1
                    if (cols.size < minSize) {
                        onUnparseable()
                        continue
                    }
                    val artist = cols[artistIdx].trim().trim('"', '\ufeff')
                    val albumArtist = cols.getOrNull(albumArtistIdx)?.trim()?.trim('"').orEmpty()
                    val title = cols[trackIdx].trim().trim('"')
                    val album = cols.getOrNull(albumIdx)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
                    val playedAt = parseTimestamp(cols[tsIdx].trim().trim('"'))
                    if (playedAt == null || title.isBlank() || (artist.isBlank() && albumArtist.isBlank())) {
                        onUnparseable()
                        continue
                    }
                    val primaryArtist = artist.ifBlank { albumArtist }
                    val dedupKey = "${primaryArtist.lowercase(Locale.ROOT)}\u0001${title.lowercase(Locale.ROOT)}\u0001${album.orEmpty().lowercase(Locale.ROOT)}\u0001$playedAt"
                    if (seenKeys != null && !seenKeys.add(dedupKey)) {
                        onDuplicate()
                        continue
                    }
                    val safeFallback = albumArtist.takeIf { isSafeArtistFallback(primaryArtist, it) }
                    result.add(ParsedRow(primaryArtist, title, album, playedAt, safeFallback))
                }

                CsvFormat.ITUNES -> {
                    if (cols.size < 4) {
                        onUnparseable()
                        continue
                    }
                    val artist = cols[0].trim().trim('"', '\ufeff')
                    val title = cols[1].trim().trim('"')
                    val album = cols[2].trim().trim('"')
                    val playedAt = parseTimestamp(cols[3].trim().trim('"'))
                    if (playedAt == null) {
                        onUnparseable()
                        continue
                    }
                    result.add(ParsedRow(artist, title, album.takeIf { it.isNotBlank() }, playedAt))
                }
            }
        }
        return result
    }

    /** Simple CSV line parser handling quotes. */
    fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    current.append('\"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
