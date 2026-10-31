package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Calendar

class TimeMachineTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 15, 12, 0).toInstant(zone).toEpochMilli()

    private fun track(id: String, artist: String, album: String) = Track(
        id = id, source = Track.SOURCE_SAF, uri = "uri://$id", fileName = "$id.mp3", mimeType = "audio/mp3",
        sizeBytes = 1000, title = "Track $id", artist = artist, album = album, durationMs = 200_000,
    )

    @Test
    fun testTimeMachineMixGeneratesForPastYears() {
        val tracks = (1..8).map { track("t$it", "Artist $it", "Album $it") }

        // Past Octobers (2024 and 2022)
        val cal = Calendar.getInstance().apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        cal.set(2024, Calendar.OCTOBER, 10, 12, 0)
        val ts2024 = cal.timeInMillis

        cal.set(2022, Calendar.OCTOBER, 5, 14, 0)
        val ts2022 = cal.timeInMillis

        val history = tracks.flatMap { t ->
            listOf(
                PlayHistory(trackId = t.id, playedAt = ts2024, listenedMs = 200_000, counted = true),
                PlayHistory(trackId = t.id, playedAt = ts2022, listenedMs = 200_000, counted = true),
            )
        }

        val rec = Recommender(
            tracks = tracks,
            history = history,
            artistTags = emptyMap(),
            now = now,
        )

        val mixes = rec.mixes()
        val timeMachineMix = mixes.find { it.id == "timemachine" }

        assertNotNull("Time Machine mix should be present", timeMachineMix)
        assertEquals("Time Machine", timeMachineMix!!.title)
        assertTrue("Subtitle should mention October", timeMachineMix.subtitle.contains("October"))
        assertTrue("Mix should contain at least 5 tracks", timeMachineMix.tracks.size >= 5)
    }

    @Test
    fun testTimeMachineIgnoresCurrentYear() {
        val tracks = (1..8).map { track("t$it", "Artist $it", "Album $it") }

        // Only plays from CURRENT year (2026)
        val cal = Calendar.getInstance().apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        cal.set(2026, Calendar.OCTOBER, 2, 12, 0)
        val ts2026 = cal.timeInMillis

        val history = tracks.map { t ->
            PlayHistory(trackId = t.id, playedAt = ts2026, listenedMs = 200_000, counted = true)
        }

        val rec = Recommender(
            tracks = tracks,
            history = history,
            artistTags = emptyMap(),
            now = now,
        )

        val mixes = rec.mixes()
        val timeMachineMix = mixes.find { it.id == "timemachine" }
        assertEquals("Time Machine mix should not appear if only current year has plays", null, timeMachineMix)
    }

    @Test
    fun testTimeMachineMixGeneratesFromHistoricalDates() {
        val tracks = (1..8).map { track("t$it", "Artist $it", "Album $it") }

        val cal = Calendar.getInstance().apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        cal.set(2022, Calendar.OCTOBER, 5, 14, 0)
        val ts2022 = cal.timeInMillis

        // History only has current year plays (e.g. from Last.fm 14-day window)
        val history = tracks.map { t ->
            PlayHistory(trackId = t.id, playedAt = now, listenedMs = 200_000, counted = true)
        }
        val historicalDates = tracks.associate { it.id to ts2022 }

        val rec = Recommender(
            tracks = tracks,
            history = history,
            artistTags = emptyMap(),
            now = now,
            historicalDates = historicalDates,
        )

        val mixes = rec.mixes()
        val timeMachineMix = mixes.find { it.id == "timemachine" }
        assertNotNull("Time Machine mix should be present from historicalDates", timeMachineMix)
        assertEquals("Time Machine", timeMachineMix!!.title)
        assertTrue(timeMachineMix.tracks.size >= 5)
    }
}
