package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class RecommenderTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 9, 12, 0).toInstant(zone).toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    private fun track(id: String, artist: String, album: String, year: Int = 2000) = Track(
        id = id, source = Track.SOURCE_DRIVE, uri = "u", fileName = "$id.m4a", mimeType = null, sizeBytes = null,
        title = "Song $id", artist = artist, album = album, year = year, durationMs = 200_000, artworkUri = "art-$album",
    )

    private fun play(trackId: String, daysAgo: Double, counted: Boolean = true, listenedMs: Long = 150_000) =
        PlayHistory(trackId = trackId, playedAt = now - (daysAgo * day).toLong(), listenedMs = listenedMs, counted = counted)

    /** 6 metal artists × 2 albums × 4 songs, 4 jazz artists likewise. */
    private val tracks = (1..6).flatMap { a ->
        (1..2).flatMap { b -> (1..4).map { s -> track("m$a-$b-$s", "Metal $a", "Metal $a Album $b", 1990 + a) } }
    } + (1..4).flatMap { a ->
        (1..2).flatMap { b -> (1..4).map { s -> track("j$a-$b-$s", "Jazz $a", "Jazz $a Album $b", 1960 + a) } }
    }
    private val tags = (1..6).associate { "metal $it" to listOf("heavy metal", "metal") } +
        (1..4).associate { "jazz $it" to listOf("jazz", "bebop") }

    @Test
    fun `replay counts only plays past half, per month`() {
        val history = listOf(
            play("m1-1-1", 1.0), play("m1-1-1", 2.0), play("m1-1-2", 3.0),
            play("m1-1-3", 4.0, counted = false, listenedMs = 10_000), // skipped: not a play
            play("j1-1-1", 40.0), // September
        )
        val byId = tracks.associateBy { it.id }
        val october = replay(history, byId, ReplayPeriod(2026, 10), zone)
        assertEquals(3, october.plays)
        assertEquals("m1-1-1", october.topTracks.first().item.id)
        assertEquals(2, october.topTracks.first().plays)
        assertEquals(listOf(8, 10), replay(history, byId, ReplayPeriod(2026), zone).months)
        assertEquals(4, replay(history, byId, ReplayPeriod(2026), zone).plays)
    }

    @Test
    fun `replay insights - months, new artists, on repeat, comparison`() {
        val history = listOf(
            play("m1-1-1", 1.0), play("m1-1-1", 1.1), play("m1-1-1", 1.2), // three times on 8 October
            play("m2-1-1", 2.0), // Metal 2: first ever play this month
            play("m1-1-2", 40.0), play("j1-1-1", 41.0), // late August
        )
        val byId = tracks.associateBy { it.id }
        val october = replay(history, byId, ReplayPeriod(2026, 10), zone)
        assertEquals(4, october.monthPlays[9])
        assertEquals(2, october.monthPlays[7])
        assertEquals(2, october.artists)
        assertEquals(1, october.newArtists) // Metal 1 was already played in August
        assertEquals("m1-1-1", october.onRepeat?.track?.id)
        assertEquals(3, october.onRepeat?.plays)
        assertEquals(null, october.previousPlays) // nothing in September
        val year = replay(history, byId, ReplayPeriod(2026), zone)
        assertEquals(3, year.artists)
        assertEquals(3, year.newArtists)
        assertEquals(6, year.monthPlays.sum())
    }

    @Test
    fun `favourites lift their genre and get a mix when forgotten for half a year`() {
        // A few recent metal plays, and a dozen jazz favourites not played in a year.
        val history = (1..3).map { play("m${it}-1-1", it.toDouble()) } + (1..12).map { play("j${it % 4 + 1}-1-${it % 4 + 1}", 400.0) }
        // Three songs of each jazz artist.
        val favourites = (1..4).flatMap { a -> (1..3).map { s -> "j$a-1-$s" } }.toSet()
        val mixes = Recommender(tracks, history, tags, now, favourites).mixes()
        val forgotten = mixes.first { it.id == "forgotten" }
        assertTrue(forgotten.tracks.all { it.id in favourites })
        assertTrue(forgotten.tracks.size >= 8)
        // The favourites put jazz ahead of metal; on plays alone, metal leads.
        fun genreOrder(m: List<Mix>) = m.filter { it.id.startsWith("genre:") }.map { it.id }
        assertEquals("genre:jazz", genreOrder(mixes).first())
        assertEquals("genre:heavy metal", genreOrder(Recommender(tracks, history, tags, now).mixes()).first())
    }

    @Test
    fun `favourites recently played are not forgotten, and a mix has at most a third of favourites`() {
        val history = (1..20).map { play("m${it % 6 + 1}-1-${it % 4 + 1}", it.toDouble() / 4) }
        // Half the metal songs are favourites.
        val favourites = tracks.filter { it.artist!!.startsWith("Metal") && it.id.endsWith("-1") || it.id.endsWith("-2") }
            .filter { it.artist!!.startsWith("Metal") }.map { it.id }.toSet()
        val mixes = Recommender(tracks, history, tags, now, favourites).mixes()
        val played = history.map { it.trackId }.toSet()
        mixes.firstOrNull { it.id == "forgotten" }?.let { f -> assertTrue(f.tracks.none { it.id in played }) }
        val genre = mixes.first { it.id == "genre:heavy metal" }
        assertTrue(genre.tracks.count { it.id in favourites } <= 10)
        assertTrue(genre.tracks.size >= 15) // 6 artists, 3 songs each at most
        // All candidates favourites: the mix still fills up.
        val allFav = tracks.filter { it.artist!!.startsWith("Metal") }.map { it.id }.toSet()
        assertTrue(Recommender(tracks, history, tags, now, allFav).mixes().first { it.id == "genre:heavy metal" }.tracks.size >= 18)
    }

    @Test
    fun `a skipped favourite is not dropped as disliked`() {
        val skips = (1..3).map { play("m1-1-1", it.toDouble(), counted = false, listenedMs = 5_000) }
        val history = skips + (1..20).map { play("m${it % 6 + 1}-2-1", it.toDouble()) }
        val withFav = Recommender(tracks, history, tags, now, setOf("m1-1-1")).mixes().flatMap { it.tracks }.map { it.id }
        val without = Recommender(tracks, history, tags, now).mixes().flatMap { it.tracks }.map { it.id }
        assertFalse("m1-1-1" in without)
        assertTrue("m1-1-1" in withFav)
    }

    @Test
    fun `mixes follow the listener's genre, cap artists and avoid repeats in a row`() {
        // Lots of metal listening, a little jazz.
        val history = (1..20).map { play("m${it % 6 + 1}-1-1", it.toDouble()) } + play("j1-1-1", 5.0)
        val mixes = Recommender(tracks, history, tags, now).mixes()
        val genre = mixes.first { it.id.startsWith("genre:") }
        assertEquals("genre:heavy metal", genre.id)
        assertTrue(genre.tracks.all { it.artist!!.startsWith("Metal") })
        assertTrue(genre.tracks.groupingBy { it.artist }.eachCount().values.all { it <= 3 })
        genre.tracks.zipWithNext().forEach { (a, b) -> assertFalse("same artist twice in a row", a.artist == b.artist) }
        val because = mixes.first { it.id.startsWith("because:") }
        assertTrue(because.tracks.count { it.artist!!.startsWith("Metal") } > because.tracks.size / 2)
    }

    @Test
    fun `songs skipped repeatedly are left out, mixes are stable within a day`() {
        val history = listOf(
            play("m1-1-1", 1.0, counted = false, listenedMs = 5_000),
            play("m1-1-1", 2.0, counted = false, listenedMs = 8_000),
        ) + (1..10).map { play("m2-1-1", it.toDouble()) }
        val first = Recommender(tracks, history, tags, now).mixes()
        assertTrue(first.flatMap { it.tracks }.none { it.id == "m1-1-1" })
        val again = Recommender(tracks, history, tags, now + 60_000).mixes()
        assertEquals(first.map { m -> m.tracks.map { it.id } }, again.map { m -> m.tracks.map { it.id } })
    }

    @Test
    fun `a library the size of the real one doesn't break the sorting`() {
        // Regression: random tie-breaks inside a sort comparator crashed with 4,600 tracks.
        val big = (1..600).flatMap { a -> (1..8).map { s -> track("b$a-$s", "Artist ${a % 150}", "Album $a", 1960 + a % 60) } }
        val bigTags = (0 until 150).associate { "artist $it" to listOf("genre ${it % 7}", "genre ${it % 5 + 10}") }
        repeat(5) { offset ->
            Recommender(big, emptyList(), bigTags, now + offset * day).mixes()
            Recommender(big, (1..200).map { play("b${it * 3}-1", it / 3.0) }, bigTags, now + offset * day).mixes()
        }
    }

    @Test
    fun `genre mixes use the file's genre tag before MusicBrainz tags`() {
        val tagged = tracks.map { if (it.artist!!.startsWith("Metal")) it.copy(genre = "Power Metal") else it }
        val history = (1..20).map { play("m${it % 6 + 1}-1-1", it.toDouble()) }
        val genre = Recommender(tagged, history, tags, now).mixes().first { it.id.startsWith("genre:") }
        assertEquals("genre:power metal", genre.id)
        assertEquals("Power Metal Mix", genre.title)
    }

    @Test
    fun `without history the library itself drives the mixes`() {
        val mixes = Recommender(tracks, emptyList(), tags, now).mixes()
        assertTrue(mixes.any { it.id.startsWith("genre:") })
        assertTrue(mixes.any { it.id == "fresh" && it.title == "Discover Your Library" })
        assertTrue(mixes.none { it.id.startsWith("because:") || it.id == "rediscover" })
    }
}
