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
    fun `without history the library itself drives the mixes`() {
        val mixes = Recommender(tracks, emptyList(), tags, now).mixes()
        assertTrue(mixes.any { it.id.startsWith("genre:") })
        assertTrue(mixes.any { it.id == "fresh" && it.title == "Discover Your Library" })
        assertTrue(mixes.none { it.id.startsWith("because:") || it.id == "rediscover" })
    }
}
