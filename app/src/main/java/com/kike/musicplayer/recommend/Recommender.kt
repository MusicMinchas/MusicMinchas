package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.ArtistInfo
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import java.util.Locale
import java.util.Random

/**
 * Lower-cased genre of a track: the file's genre tag, else its artist's top MusicBrainz tag.
 * Shared by the genre mixes and the Genres view.
 */
/** The genre key of songs with no genre at all. */
const val UNKNOWN_GENRE = "\u0000unknown"

/** Artist genre tags by lower-cased artist name; one- and two-letter tags are country codes and the like ("be", "uk"). */
fun artistTagMap(infos: List<ArtistInfo>): Map<String, List<String>> =
    infos.associate { info -> info.name to info.tagList.filter { it.length > 2 } }

fun effectiveGenreKey(t: Track, artistTags: Map<String, List<String>>): String? =
    t.genre?.lowercase(Locale.ROOT)
        ?: artistTags[(t.albumArtist ?: t.artist ?: "").lowercase(Locale.ROOT)]?.firstOrNull()
        ?: t.artist?.let { artistTags[it.lowercase(Locale.ROOT)]?.firstOrNull() }

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
 *
 * Favourites are an explicit, lasting "I like this": each adds [FAVOURITE_WEIGHT] to its song's
 * weight (artist, genre and decade affinity) without fading, favourite songs score [FAVOURITE_BONUS]
 * more in mixes (at most a third of a mix), they are never treated as disliked, and those not played
 * for half a year make their own mix.
 */
class Recommender(
    private val tracks: List<Track>,
    history: List<PlayHistory>,
    /** Lower-cased artist name -> tags, most voted first. */
    private val artistTags: Map<String, List<String>>,
    private val now: Long = System.currentTimeMillis(),
    /** Track ids of the favourite songs. */
    private val favourites: Set<String> = emptySet(),
) {
    private val stats = ListeningStats(history, now)
    private val day = now / ListeningStats.DAY_MS

    /** Something to go on: plays or favourites. */
    private val hasTaste = stats.hasHistory || favourites.isNotEmpty()

    private val usable = tracks.filter { it.durationMs >= 0 && (it.id in favourites || !stats.isDisliked(it.id)) }

    /** How much a song counts towards its artist, genre and decade: recent plays, all plays, favourite. */
    private fun weight(t: Track) =
        (stats.recentWeight[t.id] ?: 0.0) + 0.1 * (stats.plays[t.id] ?: 0) + if (t.id in favourites) FAVOURITE_WEIGHT else 0.0
    private val byArtist: Map<String, List<Track>> = usable.groupBy { artistKey(it) }

    /** How much the user listens to each artist; with no history yet, how much of it is in the library. */
    private val artistAffinity: Map<String, Double> = run {
        val fromPlays = HashMap<String, Double>()
        for (t in usable) {
            val w = weight(t)
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
        forgottenFavourites()?.let { add(it) }
        freshPicks()?.let { add(it) }
    }.filter { it.tracks.size >= MIN_MIX_SIZE }

    // ---- Mixes ----

    /** For the two most listened artists: songs by artists with similar genres, plus some of theirs. */
    private fun becauseYouListened(): List<Mix> {
        if (!hasTaste) return emptyList()
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
        // Grouped by each track's genre: the file's tag, else the artist's top MusicBrainz tag.
        val byGenre = usable.groupBy { genreKey(it) }.filterKeys { it != null }.mapKeys { it.key!! }
        val score = byGenre.mapValues { (_, list) -> if (hasTaste) list.sumOf(::weight) else list.size.toDouble() }
        return score.entries.filter { it.value > 0 && (byGenre[it.key]?.size ?: 0) >= MIN_POOL }
            .sortedByDescending { it.value }.take(3)
            .map { (key, _) ->
                val random = random("genre:$key")
                val picked = pick(byGenre.getValue(key), MIX_SIZE, perArtist = 3, random) { t ->
                    0.6 * affinity(t) + 0.5 * novelty(t)
                }
                Mix(
                    id = "genre:$key",
                    title = "${genreName(key, byGenre.getValue(key))} Mix",
                    subtitle = artistSummary(picked),
                    tracks = interleave(picked, random),
                )
            }
    }

    /** The decades the user plays most (or the library's biggest). */
    private fun decadeMixes(): List<Mix> {
        val inDecade = usable.filter { (it.year ?: 0) in 1900..2100 }.groupBy { it.year!! / 10 * 10 }
        val score = inDecade.mapValues { (_, list) -> if (hasTaste) list.sumOf(::weight) else list.size.toDouble() }
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

    /** Favourite songs not played for half a year (or never, here). */
    private fun forgottenFavourites(): Mix? {
        val forgotten = usable.filter { t ->
            t.id in favourites && (stats.daysSincePlayed(t.id) ?: Double.MAX_VALUE) > FORGOTTEN_AFTER_DAYS
        }
        if (forgotten.size < MIN_MIX_SIZE) return null
        val random = random("forgotten")
        val picked = pick(forgotten, MIX_SIZE, perArtist = 3, random, favouriteCap = false) { 0.0 }
        return Mix("forgotten", "Forgotten Favourites", "Favourites you haven't played in six months", interleave(picked, random))
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

    /** MusicBrainz tags of an artist, or its own file genres (most used first) when MusicBrainz has none. */
    private fun tagsOf(artistKey: String) = effectiveTags.getOrPut(artistKey) {
        artistTags[artistKey].orEmpty().ifEmpty {
            byArtist[artistKey].orEmpty().mapNotNull { it.genre?.lowercase(Locale.ROOT) }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        }
    }
    private val effectiveTags = HashMap<String, List<String>>()

    private fun genreKey(t: Track): String? = effectiveGenreKey(t, artistTags)

    /** The genre as the files write it most often ("Metal", not "metal"), else title-cased. */
    private fun genreName(key: String, tracks: List<Track>) =
        tracks.mapNotNull { it.genre }.filter { it.lowercase(Locale.ROOT) == key }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: titleCase(key)

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

    /**
     * Best-scored tracks with some randomness, at most [perArtist] per artist. Favourites score a
     * little higher but make up at most a third of the result (unless [favouriteCap] is off), so
     * mixes keep bringing songs beyond the known ones; more only when nothing else is left.
     */
    private fun pick(
        candidates: List<Track>,
        n: Int,
        perArtist: Int,
        random: Random,
        favouriteCap: Boolean = true,
        score: (Track) -> Double,
    ): List<Track> {
        val ranked = candidates.distinctBy { it.id }
            .map { it to score(it) + (if (it.id in favourites) FAVOURITE_BONUS else 0.0) + random.nextDouble() * JITTER }
            .sortedByDescending { it.second }
            .map { it.first }
        val perArtistCount = HashMap<String, Int>()
        val picked = ArrayList<Track>(n)
        fun take(maxFavourites: Int) {
            var favouritesTaken = picked.count { it.id in favourites }
            for (t in ranked) {
                if (picked.size >= n) return
                if (t in picked) continue
                val favourite = t.id in favourites
                if (favourite && favouritesTaken >= maxFavourites) continue
                if ((perArtistCount[artistKey(t)] ?: 0) >= perArtist) continue
                perArtistCount.merge(artistKey(t), 1, Int::plus)
                picked += t
                if (favourite) favouritesTaken++
            }
        }
        take(if (favouriteCap) (n + 2) / 3 else n)
        take(n) // too few other songs: fill up with more favourites
        return picked
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
        const val FAVOURITE_WEIGHT = 0.5
        const val FAVOURITE_BONUS = 0.4
        const val FORGOTTEN_AFTER_DAYS = 182.0

        /** Rank-weighted overlap of two tag lists (1 = same top tags). */
        fun similarity(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            fun weights(tags: List<String>) = tags.mapIndexed { i, t -> t to 1.0 / (i + 1) }.toMap()
            val wa = weights(a)
            val wb = weights(b)
            val shared = wa.keys.intersect(wb.keys).sumOf { minOf(wa.getValue(it), wb.getValue(it)) }
            return shared / minOf(wa.values.sum(), wb.values.sum())
        }

        fun titleCase(tag: String) = tag.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}
