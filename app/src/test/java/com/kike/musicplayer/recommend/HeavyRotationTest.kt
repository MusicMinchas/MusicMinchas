package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class HeavyRotationTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 23, 12, 0).toInstant(zone).toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    private fun track(
        id: String,
        artist: String,
        album: String,
        title: String = "Song $id",
        source: String = Track.SOURCE_DRIVE,
        scope: Int = Track.SCOPE_DRIVE,
    ) = Track(
        id = id,
        source = source,
        scope = scope,
        uri = "u:$id",
        fileName = "$id.m4a",
        mimeType = null,
        sizeBytes = null,
        title = title,
        artist = artist,
        album = album,
        year = 2020,
        durationMs = 240_000,
        artworkUri = "art-$album",
    )

    private fun play(
        trackId: String,
        daysAgo: Double,
        counted: Boolean = true,
        listenedMs: Long = 180_000,
    ) = PlayHistory(
        trackId = trackId,
        playedAt = now - (daysAgo * day).toLong(),
        listenedMs = listenedMs,
        counted = counted,
    )

    @Test
    fun `heavyRotation ignores plays older than 30 days`() {
        val t1 = track("t1", "Artist 1", "Album 1")
        val t2 = track("t2", "Artist 2", "Album 2")
        val tracks = mapOf(t1.id to t1, t2.id to t2)

        val history = listOf(
            play("t1", daysAgo = 2.0, counted = true),
            play("t1", daysAgo = 3.0, counted = true),
            // t2 has 10 plays, but all 35 days ago (outside 30-day window)
            play("t2", daysAgo = 35.0, counted = true),
            play("t2", daysAgo = 36.0, counted = true),
            play("t2", daysAgo = 37.0, counted = true),
        )

        val rotation = heavyRotation(tracks, history, now = now)
        assertEquals(1, rotation.size)
        assertEquals("t1", rotation.first().item.id)
    }

    @Test
    fun `recent velocity outranks older play count`() {
        val tRecent = track("tRecent", "Artist Recent", "Album Recent")
        val tOld = track("tOld", "Artist Old", "Album Old")
        val tracks = mapOf(tRecent.id to tRecent, tOld.id to tOld)

        // tRecent was played 3 times in the last 2 days
        // tOld was played 8 times 26 days ago
        val history = listOf(
            play("tRecent", daysAgo = 0.5),
            play("tRecent", daysAgo = 1.0),
            play("tRecent", daysAgo = 1.5),
        ) + (1..8).map { play("tOld", daysAgo = 26.0 + it * 0.1) }

        val rotation = heavyRotation(tracks, history, now = now)
        assertEquals(2, rotation.size)
        assertEquals("tRecent", rotation[0].item.id)
        assertEquals("tOld", rotation[1].item.id)
    }

    @Test
    fun `multi-day consistency provides bonus over single day loop`() {
        val tMulti = track("tMulti", "Artist Multi", "Album Multi")
        val tSingle = track("tSingle", "Artist Single", "Album Single")
        val tracks = mapOf(tMulti.id to tMulti, tSingle.id to tSingle)

        // Both have 4 plays around 5 days ago, but tMulti is across 4 distinct days, tSingle is all in 1 day
        val history = listOf(
            play("tMulti", daysAgo = 3.0),
            play("tMulti", daysAgo = 4.0),
            play("tMulti", daysAgo = 5.0),
            play("tMulti", daysAgo = 6.0),
            // tSingle all played on day 4.5
            play("tSingle", daysAgo = 4.5),
            play("tSingle", daysAgo = 4.52),
            play("tSingle", daysAgo = 4.54),
            play("tSingle", daysAgo = 4.56),
        )

        val rotation = heavyRotation(tracks, history, now = now)
        assertEquals("tMulti", rotation.first().item.id)
    }

    @Test
    fun `early skips penalize and disliked tracks are excluded`() {
        val tGood = track("tGood", "Artist A", "Album A")
        val tSkipped = track("tSkipped", "Artist B", "Album B")
        val tDisliked = track("tDisliked", "Artist C", "Album C")
        val tracks = mapOf(tGood.id to tGood, tSkipped.id to tSkipped, tDisliked.id to tDisliked)

        val history = listOf(
            play("tGood", daysAgo = 1.0, counted = true),
            play("tGood", daysAgo = 2.0, counted = true),
            // tSkipped has 2 plays and 1 early skip (-0.5 penalty)
            play("tSkipped", daysAgo = 1.0, counted = true),
            play("tSkipped", daysAgo = 2.0, counted = true),
            play("tSkipped", daysAgo = 2.5, counted = false, listenedMs = 5_000),
            // tDisliked has 2 early skips and 0 counted plays
            play("tDisliked", daysAgo = 3.0, counted = false, listenedMs = 4_000),
            play("tDisliked", daysAgo = 3.1, counted = false, listenedMs = 5_000),
        )

        val rotation = heavyRotation(tracks, history, now = now)
        assertEquals(2, rotation.size)
        assertEquals("tGood", rotation[0].item.id)
        assertEquals("tSkipped", rotation[1].item.id)
        assertTrue(rotation.none { it.item.id == "tDisliked" })
    }

    @Test
    fun `multi-source deduplication aggregates plays and own copy wins`() {
        // Same song played from Navidrome (Subsonic) and Google Drive
        val driveTrack = track("drive:song1", "Evanescence", "Fallen", title = "Going Under", source = Track.SOURCE_DRIVE, scope = Track.SCOPE_DRIVE)
        val serverTrack = track("subsonic:song1", "Evanescence", "Fallen", title = "Going Under", source = Track.SOURCE_SUBSONIC, scope = Track.SCOPE_SERVER)
        val tracks = mapOf(driveTrack.id to driveTrack, serverTrack.id to serverTrack)

        val history = listOf(
            play("drive:song1", daysAgo = 1.0),
            play("drive:song1", daysAgo = 2.0),
            play("subsonic:song1", daysAgo = 1.5),
            play("subsonic:song1", daysAgo = 2.5),
        )

        val rotation = heavyRotation(tracks, history, now = now)
        assertEquals(1, rotation.size)
        // Aggregated plays: 4
        assertEquals(4, rotation.first().plays)
        // Own copy wins: drive track picked over subsonic server track
        assertEquals("drive:song1", rotation.first().item.id)
        assertEquals(Track.SCOPE_DRIVE, rotation.first().item.scope)
    }

    @Test
    fun `diversity ceiling enforces max 2 per artist and max 2 per album`() {
        // An album with 5 songs, all heavily played
        val albumTracks = (1..5).map {
            track("ev$it", "Evanescence", "The Open Door", title = "Song $it")
        }
        val otherTrack = track("other1", "In Flames", "Come Clarity", title = "Take This Life")
        val tracks = (albumTracks + otherTrack).associateBy { it.id }

        val history = albumTracks.flatMap { t ->
            listOf(play(t.id, daysAgo = 1.0), play(t.id, daysAgo = 2.0))
        } + listOf(play("other1", daysAgo = 3.0))

        val rotation = heavyRotation(tracks, history, now = now, maxPerArtist = 2, maxPerAlbum = 2)
        val evCount = rotation.count { it.item.artist == "Evanescence" }
        assertEquals(2, evCount)
        assertTrue(rotation.any { it.item.id == "other1" })
    }

    @Test
    fun `heavyRotationCandidateIds returns active candidates from history`() {
        val history = listOf(
            play("t1", daysAgo = 1.0),
            play("t1", daysAgo = 2.0),
            play("t2", daysAgo = 3.0),
            play("tOld", daysAgo = 40.0), // outside window
        )

        val candidates = heavyRotationCandidateIds(history, now = now, candidateLimit = 10)
        assertEquals(listOf("t1", "t2"), candidates)
    }
}
