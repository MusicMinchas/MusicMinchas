package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import java.util.Locale
import java.util.Random

/** A generated playlist on the Home tab. [id] is stable for a given mix kind and seed (artist, genre...). */
data class Mix(val id: String, val title: String, val subtitle: String, val tracks: List<Track>) {
    val covers: List<String> get() = tracks.mapNotNull { it.artworkUri }.distinct().take(4)
}

/**
 * On-device recommendations from the library, the play history and artist genre tags
 * (MusicBrainz). Everything runs locally; nothing about listening leaves the phone.
 *
 * Signals: counted plays (decaying with age) give artist and genre affinity; recently played tracks
 * are pushed back; tracks skipped repeatedly are left out; artists are capped per mix. Mixes are
 * seeded by the day, so they stay the same through a day and change the next.
 */
class Recommender(
    private val tracks: List<Track>,
    history: List<PlayHistory>,
    /** Lower-cased artist name -> tags, most voted first. */
    private val artistTags: Map<String, List<String>>,
    private val now: Long = System.currentTimeMillis(),
) {
    private val stats = ListeningStats(history, now)
    private val day = now / ListeningStats.DAY_MS

    private val usable = tracks.filter { it.durationMs >= 0 && !stats.isDisliked(it.id) }
    private val byArtist: Map<String, List<Track>> = usable.groupBy { artistKey(it) }

    /** How much the user listens to each artist; with no history yet, how much of it is in the library. */
    private val artistAffinity: Map<String, Double> = run {
        val fromPlays = HashMap<String, Double>()
        for (t in usable) {
            val w = (stats.recentWeight[t.id] ?: 0.0) + 0.1 * (stats.plays[t.id] ?: 0)
            if (w > 0) fromPlays.merge(artistKey(t), w, Double::plus)
        }
        if (fromPlays.isNotEmpty()) fromPlays else byArtist.mapValues { (_, list) -> list.size / 100.0 }
    }
    private val maxAffinity = artistAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    fun mixes(): List<Mix> = buildList {
        addAll(becauseYouListened())
        addAll(genreMixes())
        addAll(decadeMixes())
        rediscover()?.let { add(it) }
        freshPicks()?.let { add(it) }
    }.filter { it.tracks.size >= MIN_MIX_SIZE }

    // ---- Mixes ----

    /** For the two most listened artists: songs by artists with similar genres, plus some of theirs. */
    private fun becauseYouListened(): List<Mix> {
        if (!stats.hasHistory) return emptyList()
        return artistAffinity.entries.sortedByDescending { it.value }.take(2).mapNotNull { (seed, _) ->
            val seedTags = tagsOf(seed).take(TAGS_PER_ARTIST)
            if (seedTags.isEmpty()) return@mapNotNull null
            val similar = byArtist.keys.filter { it != seed }
                .map { it to similarity(seedTags, tagsOf(it).take(TAGS_PER_ARTIST)) }
                .filter { it.second >= MIN_SIMILARITY }
                .sortedByDescending { it.second }
                .take(10)
                .toMap()
            if (similar.isEmpty()) return@mapNotNull null
            val random = random("because:$seed")
            val fromSimilar = pick(similar.keys.flatMap { byArtist[it].orEmpty() }, 20, perArtist = 3, random) { t ->
                similar.getValue(artistKey(t)) + 0.5 * novelty(t) + 0.3 * affinity(t)
            }
            val fromSeed = pick(byArtist[seed].orEmpty(), 6, perArtist = 6, random) { t -> novelty(t) }
            val name = byArtist[seed]?.firstOrNull()?.let { displayArtist(it) } ?: seed
            Mix(
                id = "because:$seed",
                title = "Because you listened to $name",
                subtitle = similar.keys.take(3).mapNotNull { k -> byArtist[k]?.firstOrNull()?.let(::displayArtist) }.joinToString(", "),
                tracks = interleave(fromSimilar + fromSeed, random),
            )
        }
    }

    /** The user's top genres (or the library's, with no history yet), each as a mix. */
    private fun genreMixes(): List<Mix> {
        val tagScore = HashMap<String, Double>()
        for ((artist, affinity) in artistAffinity) {
            tagsOf(artist).take(TAGS_PER_ARTIST).forEachIndexed { i, tag -> tagScore.merge(tag, affinity / (i + 1), Double::plus) }
        }
        val chosen = mutableListOf<Pair<String, Set<String>>>()
        for ((tag, _) in tagScore.entries.sortedByDescending { it.value }) {
            if (chosen.size == 3) break
            val artists = byArtist.keys.filter { tag in tagsOf(it).take(TAGS_PER_ARTIST) }.toSet()
            if (artists.sumOf { byArtist[it]?.size ?: 0 } < MIN_POOL) continue
            // Skip near-duplicates such as "metal" right after "heavy metal".
            if (chosen.any { (_, other) -> overlap(artists, other) > 0.7 }) continue
            chosen += tag to artists
        }
        return chosen.map { (tag, artists) ->
            val random = random("genre:$tag")
            val picked = pick(artists.flatMap { byArtist[it].orEmpty() }, MIX_SIZE, perArtist = 3, random) { t ->
                0.6 * affinity(t) + 0.5 * novelty(t)
            }
            Mix(
                id = "genre:$tag",
                title = "${titleCase(tag)} Mix",
                subtitle = artistSummary(picked),
                tracks = interleave(picked, random),
            )
        }
    }

    /** The decades the user plays most (or the library's biggest). */
    private fun decadeMixes(): List<Mix> {
        val inDecade = usable.filter { (it.year ?: 0) in 1900..2100 }.groupBy { it.year!! / 10 * 10 }
        val score = inDecade.mapValues { (_, list) ->
            if (stats.hasHistory) list.sumOf { (stats.recentWeight[it.id] ?: 0.0) + 0.1 * (stats.plays[it.id] ?: 0) } else list.size.toDouble()
        }
        return score.entries.filter { (inDecade[it.key]?.size ?: 0) >= MIN_POOL }
            .sortedByDescending { it.value }.take(2)
            .map { (decade, _) ->
                val random = random("decade:$decade")
                val picked = pick(inDecade.getValue(decade), MIX_SIZE, perArtist = 2, random) { t ->
                    0.6 * affinity(t) + 0.5 * novelty(t)
                }
                Mix("decade:$decade", "${decade}s Mix", artistSummary(picked), interleave(picked, random))
            }
    }

    /** Albums played a lot that haven't been played for two months. */
    private fun rediscover(): Mix? {
        if (!stats.hasHistory) return null
        val albums = usable.filter { it.album != null }.groupBy { it.albumKey }.filter { (_, list) ->
            val plays = list.sumOf { stats.plays[it.id] ?: 0 }
            val lastDays = list.mapNotNull { stats.daysSincePlayed(it.id) }.minOrNull()
            plays >= 3 && lastDays != null && lastDays > REDISCOVER_AFTER_DAYS
        }
        if (albums.isEmpty()) return null
        val random = random("rediscover")
        val picked = albums.values.flatMap { list -> list.sortedByDescending { stats.plays[it.id] ?: 0 }.take(3) }
        return Mix("rediscover", "Rediscover", "Albums you loved but haven't played lately", interleave(picked.take(MIX_SIZE), random))
    }

    /** One or two songs each from albums never played, leaning towards the user's genres. */
    private fun freshPicks(): Mix? {
        val played = stats.lastPlayed.keys
        val albums = usable.filter { it.album != null }.groupBy { it.albumKey }.filter { (_, list) -> list.none { it.id in played } }
        if (albums.isEmpty()) return null
        val topTags = topTags(5)
        val random = random("fresh")
        // Score each album once: a random part inside the sort's comparison breaks the sort.
        val ranked = albums.values.map { list ->
            val tags = tagsOf(artistKey(list.first())).take(TAGS_PER_ARTIST)
            list to tags.count { it in topTags } + random.nextDouble()
        }.sortedByDescending { it.second }.map { it.first }
        val picked = ranked.take(15).flatMap { list -> list.shuffled(random).take(2) }
        return Mix(
            "fresh",
            if (stats.hasHistory) "Fresh Picks" else "Discover Your Library",
            "From albums you haven't played yet",
            interleave(picked.take(MIX_SIZE), random),
        )
    }

    // ---- Helpers ----

    private fun artistKey(t: Track) = (t.albumArtist ?: t.artist ?: "").lowercase(Locale.ROOT)

    private fun displayArtist(t: Track) = t.albumArtist ?: t.displayArtist

    private fun tagsOf(artistKey: String) = artistTags[artistKey].orEmpty()

    private fun affinity(t: Track) = (artistAffinity[artistKey(t)] ?: 0.0) / maxAffinity

    /** Pushes back what was played recently, so mixes bring something different. */
    private fun novelty(t: Track): Double {
        val days = stats.daysSincePlayed(t.id) ?: return 0.3
        return when {
            days < 3 -> -1.0
            days < 14 -> -0.4
            else -> 0.0
        }
    }

    private fun topTags(n: Int): Set<String> {
        val score = HashMap<String, Double>()
        for ((artist, affinity) in artistAffinity) tagsOf(artist).take(3).forEach { score.merge(it, affinity, Double::plus) }
        return score.entries.sortedByDescending { it.value }.take(n).map { it.key }.toSet()
    }

    /** Best-scored tracks with some randomness, at most [perArtist] per artist. */
    private fun pick(candidates: List<Track>, n: Int, perArtist: Int, random: Random, score: (Track) -> Double): List<Track> {
        val perArtistCount = HashMap<String, Int>()
        return candidates.distinctBy { it.id }
            .map { it to score(it) + random.nextDouble() * JITTER }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { t -> perArtistCount.merge(artistKey(t), 1, Int::plus)!! <= perArtist }
            .take(n)
    }

    /** Shuffles, then avoids the same artist twice in a row where possible. */
    private fun interleave(list: List<Track>, random: Random): List<Track> {
        val pool = list.distinctBy { it.id }.shuffled(random).toMutableList()
        val out = ArrayList<Track>(pool.size)
        while (pool.isNotEmpty()) {
            val i = pool.indexOfFirst { out.isEmpty() || artistKey(it) != artistKey(out.last()) }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(i)
        }
        return out
    }

    private fun artistSummary(list: List<Track>) =
        list.groupingBy { displayArtist(it) }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key } +
            if (list.map { artistKey(it) }.distinct().size > 3) " and more" else ""

    private fun random(mixId: String) = Random(day * 1_000_003 + mixId.hashCode())

    private companion object {
        const val MIX_SIZE = 30
        const val MIN_MIX_SIZE = 8
        const val MIN_POOL = 20
        const val TAGS_PER_ARTIST = 5
        const val MIN_SIMILARITY = 0.25
        const val REDISCOVER_AFTER_DAYS = 60.0
        const val JITTER = 0.6

        /** Rank-weighted overlap of two tag lists (1 = same top tags). */
        fun similarity(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            fun weights(tags: List<String>) = tags.mapIndexed { i, t -> t to 1.0 / (i + 1) }.toMap()
            val wa = weights(a)
            val wb = weights(b)
            val shared = wa.keys.intersect(wb.keys).sumOf { minOf(wa.getValue(it), wb.getValue(it)) }
            return shared / minOf(wa.values.sum(), wb.values.sum())
        }

        fun overlap(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / minOf(a.size, b.size)

        fun titleCase(tag: String) = tag.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}
