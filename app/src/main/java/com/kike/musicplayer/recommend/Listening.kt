package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.lastfm.TrackMatcher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-track statistics from the play history. */
class ListeningStats(
    history: List<PlayHistory>,
    private val now: Long,
    historicalDates: Map<String, Long> = emptyMap(),
    inAppPlayDates: Map<String, Long> = emptyMap(),
) {
    /** Counted plays (half the track listened), all time. */
    val plays: Map<String, Int>

    /** Counted plays, each weighted by how recent it is (halves every [HALF_LIFE_DAYS]). */
    val recentWeight: Map<String, Double>

    /** Last time each track started playing, counted or not. */
    val lastPlayed: Map<String, Long>

    /** Plays abandoned within 30 s. Plays from before listening time was recorded aren't skips. */
    val skips: Map<String, Int>

    init {
        val plays = HashMap<String, Int>()
        val weight = HashMap<String, Double>()
        val last = HashMap<String, Long>()
        val skips = HashMap<String, Int>()
        for (h in history) {
            last.merge(h.trackId, h.playedAt, ::maxOf)
            if (h.counted) {
                plays.merge(h.trackId, 1, Int::plus)
                val ageDays = (now - h.playedAt) / DAY_MS.toDouble()
                weight.merge(h.trackId, Math.pow(0.5, ageDays / HALF_LIFE_DAYS), Double::plus)
            } else if (h.listenedMs in 1 until SKIP_MS) {
                skips.merge(h.trackId, 1, Int::plus)
            }
        }
        // Apply authoritative historical last-played dates from CSV
        // Precedence: In-App Play (if after CSV date) > CSV Date > Fallback (Last.fm scrobble)
        for ((trackId, csvDate) in historicalDates) {
            val inApp = inAppPlayDates[trackId]
            if (inApp != null && inApp > csvDate) {
                last[trackId] = inApp
            } else {
                last[trackId] = csvDate
            }
        }

        this.plays = plays
        recentWeight = weight
        lastPlayed = last
        this.skips = skips
    }

    val hasHistory get() = plays.isNotEmpty()

    fun daysSincePlayed(trackId: String): Double? = lastPlayed[trackId]?.let { (now - it) / DAY_MS.toDouble() }

    /** Started at least twice, skipped each time, never listened through: keep out of mixes. */
    fun isDisliked(trackId: String) = (skips[trackId] ?: 0) >= 2 && (plays[trackId] ?: 0) == 0

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val HALF_LIFE_DAYS = 60.0
        const val SKIP_MS = 30_000L
    }
}

data class Ranked<T>(val item: T, val plays: Int)

/** Most played tracks of all time (counted plays), most played first. */
fun mostPlayed(tracks: Map<String, Track>, stats: ListeningStats, limit: Int = 50): List<Ranked<Track>> =
    stats.plays.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { stats.lastPlayed[it.key] ?: 0 })
        .mapNotNull { (id, n) -> tracks[id]?.let { Ranked(it, n) } }
        .take(limit)

const val HEAVY_ROTATION_WINDOW_DAYS = 30
const val HEAVY_ROTATION_HALF_LIFE_DAYS = 7.0
const val HEAVY_ROTATION_LIMIT = 25
const val HEAVY_ROTATION_MAX_PER_ARTIST = 2
const val HEAVY_ROTATION_MAX_PER_ALBUM = 2

/**
 * Fast pre-scoring from play history to find the top candidate track IDs for Heavy Rotation
 * without having to load the entire 30-day track history from the database.
 */
fun heavyRotationCandidateIds(history: List<PlayHistory>, now: Long, candidateLimit: Int = 100): List<String> {
    val cutoff = now - HEAVY_ROTATION_WINDOW_DAYS * ListeningStats.DAY_MS
    val scores = HashMap<String, Double>()
    val days = HashMap<String, MutableSet<Long>>()
    for (h in history) {
        if (h.playedAt < cutoff) continue
        val ageDays = (now - h.playedAt) / ListeningStats.DAY_MS.toDouble()
        if (h.counted) {
            val w = Math.pow(0.5, ageDays / HEAVY_ROTATION_HALF_LIFE_DAYS)
            scores.merge(h.trackId, w, Double::plus)
            days.getOrPut(h.trackId) { HashSet() }.add(h.playedAt / ListeningStats.DAY_MS)
        } else if (h.listenedMs in 1 until ListeningStats.SKIP_MS) {
            scores.merge(h.trackId, -0.5, Double::plus)
        }
    }
    return scores.entries
        .filter { it.value > 0 }
        .sortedByDescending { (id, score) ->
            val activeDays = days[id]?.size ?: 1
            score * (1.0 + 0.15 * (activeDays - 1))
        }
        .take(candidateLimit)
        .map { it.key }
}

/**
 * Computes the 25 tracks in 'Heavy Rotation' based on Apple Music momentum:
 * - 30-day rolling window
 * - Exponential decay (half-life of 7 days)
 * - Multi-day consistency multiplier (+15% per additional active day)
 * - Skip penalty (-0.5) and dislike exclusion
 * - Canonical song deduplication across sources (own copy wins: Local > Drive > Server > Stream)
 * - Diversity cap (max 2 tracks per artist, max 2 tracks per album)
 */
fun heavyRotation(
    tracks: Map<String, Track>,
    history: List<PlayHistory>,
    now: Long = System.currentTimeMillis(),
    limit: Int = HEAVY_ROTATION_LIMIT,
    maxPerArtist: Int = HEAVY_ROTATION_MAX_PER_ARTIST,
    maxPerAlbum: Int = HEAVY_ROTATION_MAX_PER_ALBUM,
): List<Ranked<Track>> {
    val cutoff = now - HEAVY_ROTATION_WINDOW_DAYS * ListeningStats.DAY_MS

    class SongGroup(var bestTrack: Track, var bestPrio: Int) {
        var score = 0.0
        var countedPlays = 0
        val activeDays = HashSet<Long>()
        var skips = 0
    }

    fun sourcePriority(t: Track): Int = when (t.scope) {
        Track.SCOPE_LOCAL -> 0
        Track.SCOPE_DRIVE -> 1
        Track.SCOPE_SERVER -> 2
        else -> 3
    }

    val groups = HashMap<String, SongGroup>()

    for (h in history) {
        if (h.playedAt < cutoff) continue
        val t = tracks[h.trackId] ?: continue
        if (t.durationMs < 0) continue // unplayable DRM

        val normArtist = TrackMatcher.looseArtist(t.artist ?: t.albumArtist.orEmpty())
        val normTitle = TrackMatcher.looseTitle(t.title)
        val key = "$normArtist\u0001$normTitle"

        val prio = sourcePriority(t)
        val group = groups.getOrPut(key) { SongGroup(t, prio) }
        if (prio < group.bestPrio) {
            group.bestTrack = t
            group.bestPrio = prio
        }

        val ageDays = (now - h.playedAt) / ListeningStats.DAY_MS.toDouble()
        if (h.counted) {
            group.countedPlays++
            group.activeDays.add(h.playedAt / ListeningStats.DAY_MS)
            val w = Math.pow(0.5, ageDays / HEAVY_ROTATION_HALF_LIFE_DAYS)
            group.score += w
        } else if (h.listenedMs in 1 until ListeningStats.SKIP_MS) {
            group.skips++
            group.score -= 0.5
        }
    }

    val scored = groups.values.mapNotNull { g ->
        if (g.countedPlays < 1 || (g.skips >= 2 && g.countedPlays == 0) || g.score <= 0.0) null
        else {
            val dayMult = 1.0 + 0.15 * (g.activeDays.size - 1)
            val finalScore = g.score * dayMult
            Triple(finalScore, g.bestTrack, g.countedPlays)
        }
    }.sortedByDescending { it.first }

    val selected = ArrayList<Ranked<Track>>()
    val artistCounts = HashMap<String, Int>()
    val albumCounts = HashMap<String, Int>()

    for ((_, track, plays) in scored) {
        val artKey = TrackMatcher.looseArtist(track.artist ?: track.albumArtist.orEmpty())
        val albKey = track.album?.let { TrackMatcher.basic(it) } ?: ""

        if (maxPerArtist > 0 && (artistCounts[artKey] ?: 0) >= maxPerArtist) continue
        if (maxPerAlbum > 0 && albKey.isNotEmpty() && (albumCounts[albKey] ?: 0) >= maxPerAlbum) continue

        selected.add(Ranked(track, plays))
        artistCounts.merge(artKey, 1, Int::plus)
        if (albKey.isNotEmpty()) albumCounts.merge(albKey, 1, Int::plus)

        if (selected.size >= limit) break
    }

    return selected
}

/** A Replay period: a whole year, or one month of it (1-12). */
data class ReplayPeriod(val year: Int, val month: Int? = null)

data class RankedArtist(val name: String, val plays: Int, val artworkUri: String?)

data class RankedAlbum(val key: String, val title: String, val artist: String, val artworkUri: String?, val plays: Int)

/** The song played most often in one day of the period. */
data class OnRepeat(val track: Track, val plays: Int, val day: LocalDate)

data class Replay(
    val period: ReplayPeriod,
    /** Years with counted plays (always includes the current one), newest first. */
    val years: List<Int>,
    /** Months of [period]'s year with counted plays. */
    val months: List<Int>,
    /** Counted plays in each month (index 0 = January) of [period]'s year. */
    val monthPlays: List<Int>,
    val plays: Int,
    val listenedMs: Long,
    /** Different artists played. */
    val artists: Int,
    /** Artists whose first counted play ever falls in the period. */
    val newArtists: Int,
    /** Hour of the day (0-23) with the most plays, or null without plays. */
    val peakHour: Int?,
    val onRepeat: OnRepeat?,
    /** The genre with the most plays (file genres), or null. */
    val topGenre: String?,
    /** Counted plays in the period before (the previous month, or the previous year); null for the first. */
    val previousPlays: Int?,
    val topTracks: List<Ranked<Track>>,
    val topArtists: List<RankedArtist>,
    val topAlbums: List<RankedAlbum>,
)

/** Replay: the most played tracks, artists and albums of a year or a month, by counted plays. */
fun replay(history: List<PlayHistory>, tracks: Map<String, Track>, period: ReplayPeriod?, zone: ZoneId = ZoneId.systemDefault()): Replay {
    fun dateOf(h: PlayHistory) = Instant.ofEpochMilli(h.playedAt).atZone(zone)
    val currentYear = Instant.now().atZone(zone).year
    val counted = history.filter { it.counted }
    val years = (counted.map { dateOf(it).year } + currentYear).distinct().sortedDescending()
    val selected = period ?: ReplayPeriod(currentYear)

    val yearStartMs = LocalDate.of(selected.year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val yearEndMs = LocalDate.of(selected.year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val inYear = history.filter { it.playedAt in yearStartMs until yearEndMs }
    val months = inYear.filter { it.counted }.map { dateOf(it).monthValue }.distinct().sorted()
    val inPeriod = if (selected.month == null) inYear else inYear.filter { dateOf(it).monthValue == selected.month }
    val playsByTrack = inPeriod.filter { it.counted }.groupingBy { it.trackId }.eachCount()

    val topTracks = playsByTrack.entries.sortedByDescending { it.value }
        .mapNotNull { (id, n) -> tracks[id]?.let { Ranked(it, n) } }
    val byArtist = topTracks.groupBy { it.item.albumArtist ?: it.item.displayArtist }
    val topArtists = byArtist.map { (name, list) ->
        RankedArtist(name, list.sumOf { it.plays }, list.firstNotNullOfOrNull { it.item.artworkUri })
    }.sortedByDescending { it.plays }
    val topAlbums = topTracks.filter { it.item.album != null }.groupBy { it.item.albumKey }.map { (key, list) ->
        val first = list.first().item
        RankedAlbum(key, first.album!!, first.albumArtist ?: first.displayArtist, list.firstNotNullOfOrNull { it.item.artworkUri }, list.sumOf { it.plays })
    }.sortedByDescending { it.plays }

    val countedInPeriod = inPeriod.filter { it.counted }
    val monthPlays = IntArray(12).also { m -> inYear.filter { it.counted }.forEach { m[dateOf(it).monthValue - 1]++ } }.toList()

    // An artist is new when their first counted play ever is in the period.
    fun artistOf(h: PlayHistory) = tracks[h.trackId]?.let { it.albumArtist ?: it.displayArtist }
    val firstPlay = HashMap<String, Long>()
    counted.forEach { h -> artistOf(h)?.let { firstPlay.merge(it, h.playedAt, ::minOf) } }
    val periodArtists = countedInPeriod.mapNotNullTo(HashSet(), ::artistOf)
    val periodStart = if (selected.month == null) LocalDate.of(selected.year, 1, 1) else LocalDate.of(selected.year, selected.month, 1)
    val periodStartMs = periodStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val newArtists = periodArtists.count { (firstPlay[it] ?: Long.MAX_VALUE) >= periodStartMs }

    val peakHour = countedInPeriod.groupingBy { dateOf(it).hour }.eachCount().maxByOrNull { it.value }?.key
    val onRepeat = countedInPeriod.groupingBy { it.trackId to dateOf(it).toLocalDate() }.eachCount()
        .maxByOrNull { it.value }?.takeIf { it.value >= 2 }
        ?.let { (key, n) -> tracks[key.first]?.let { OnRepeat(it, n, key.second) } }
    val topGenre = countedInPeriod.mapNotNull { tracks[it.trackId]?.genre?.takeIf(String::isNotBlank) }
        .groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value }?.key
        ?.let { key -> countedInPeriod.firstNotNullOf { h -> tracks[h.trackId]?.genre?.takeIf { it.lowercase() == key } } }

    val previous = if (selected.month == null) ReplayPeriod(selected.year - 1)
    else if (selected.month == 1) ReplayPeriod(selected.year - 1, 12) else ReplayPeriod(selected.year, selected.month - 1)
    val previousPlays = counted.count { h ->
        val d = dateOf(h)
        d.year == previous.year && (previous.month == null || d.monthValue == previous.month)
    }.takeIf { it > 0 }

    return Replay(
        period = selected,
        years = years,
        months = months,
        monthPlays = monthPlays,
        plays = playsByTrack.values.sum(),
        listenedMs = inPeriod.sumOf { it.listenedMs },
        artists = periodArtists.size,
        newArtists = newArtists,
        peakHour = peakHour,
        onRepeat = onRepeat,
        topGenre = topGenre,
        previousPlays = previousPlays,
        topTracks = topTracks.take(50),
        topArtists = topArtists.take(10),
        topAlbums = topAlbums.take(10),
    )
}
