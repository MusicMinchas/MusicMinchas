package com.kike.musicplayer.data.download

import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNamesTest {
    private fun track(title: String, fileName: String, number: Int? = null, disc: Int? = null, mime: String? = "audio/mp4") = Track(
        id = "drive:x", source = Track.SOURCE_DRIVE, uri = "u", fileName = fileName, mimeType = mime, sizeBytes = 1,
        title = title, trackNumber = number, discNumber = disc,
    )

    @Test
    fun `files are numbered like iTunes and keep their extension`() {
        assertEquals("01 Going Under.m4a", Downloader.fileName(track("Going Under", "01 Going Under.m4a", 1)))
        assertEquals("13 Missing (Bonus).mp3", Downloader.fileName(track("Missing (Bonus)", "13 Missing (Bonus).MP3", 13, 1)))
        assertEquals("2-04 Missionary Man.m4a", Downloader.fileName(track("Missionary Man", "x.m4a", 4, 2)))
        assertEquals("Intro.flac", Downloader.fileName(track("Intro", "noext", mime = "audio/flac")))
    }

    @Test
    fun `names are safe for folders and files`() {
        assertEquals("AC_DC", Downloader.safe("AC/DC"))
        assertEquals("What_ Why_", Downloader.safe("What? Why*"))
        assertEquals("Live Shit_ Binge & Purge", Downloader.safe("Live Shit: Binge & Purge"))
        assertEquals("Vol. 1", Downloader.safe("Vol. 1..."))
        assertEquals("_", Downloader.safe("  "))
    }
}
