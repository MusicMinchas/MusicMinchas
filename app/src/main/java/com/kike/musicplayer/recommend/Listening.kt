package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-track statistics from the play history. */
class ListeningStats(history: List<PlayHistory>, private val now: Long) {
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

    val inYear = history.filter { dateOf(it).year == selected.year }
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
