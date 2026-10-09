package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import java.time.Instant
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

data class Replay(
    val period: ReplayPeriod,
    /** Years with counted plays (always includes the current one), newest first. */
    val years: List<Int>,
    /** Months of [period]'s year with counted plays. */
    val months: List<Int>,
    val plays: Int,
    val listenedMs: Long,
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

    return Replay(
        period = selected,
        years = years,
        months = months,
        plays = playsByTrack.values.sum(),
        listenedMs = inPeriod.sumOf { it.listenedMs },
        topTracks = topTracks.take(50),
        topArtists = topArtists.take(10),
        topAlbums = topAlbums.take(10),
    )
}
