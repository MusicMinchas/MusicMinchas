package com.kike.musicplayer.data.lastfm

import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMatcherTest {
    private fun track(id: String, artist: String, title: String, album: String? = null, albumArtist: String? = null, mbid: String? = null) =
        Track(
            id = id, source = Track.SOURCE_DRIVE, uri = "u", fileName = "f", mimeType = null, sizeBytes = null,
            title = title, artist = artist, album = album, albumArtist = albumArtist, mbRecordingId = mbid, durationMs = 200_000,
        )

    // Names as they are in the library.
    private val library = listOf(
        track("bring", "Evanescence", "Bring Me To Life (Feat. Paul McCoy)", "Fallen"),
        track("mauvaise", "Stromae", "Mauvaise journée", "Multitude"),
        track("battery", "Metallica", "Battery", "Master of Puppets (Remastered)"),
        track("whiplash", "Metallica", "Whiplash", "Kill 'Em All"),
        track("whiplash-live", "Metallica", "Whiplash (Live)", "Live Shit: Binge & Purge"),
        track("easily", "Red Hot Chili Peppers", "Easily", "Californication"),
        track("immortal", "Evanescence", "My Immortal", "Fallen"),
        track("immortal-anywhere", "Evanescence", "My Immortal", "Anywhere But Home"),
        track("smooth", "Michael Jackson", "Smooth Criminal", "Bad", mbid = "mbid-smooth"),
        track("girl", "Michael Jackson & Paul McCartney", "The Girl Is Mine", "Thriller", albumArtist = "Michael Jackson"),
        // Real near-misses from the library (tags vs Last.fm spelling).
        track("olla", "Luar Na Lubre", "Olla meu irmau", "Saudade"),
        track("uah", "Luar Na Lubre", "Uah lúa (Folla do Visgo)", "Hai Un Paraiso"),
        track("uah-remix", "Luar Na Lubre", "Uah lúa (Folla do Visgo) (Remix, Bonus Track)", "Hai Un Paraiso"),
        track("pretending", "HIM", "Pretending (TRNSFRMTN version)", "Deep Shadows And Brilliant Highlights"),
        track("place", "Linkin Park", "A Place for My Head", "Hybrid Theory (20th Anniversary Edition)"),
        track("darkshines", "Muse", "Darkshines", "Origin of Symmetry"),
        track("exo3", "Muse", "Exogenesis: Symphony Part 3 (Redemption)", "The Resistance"),
        track("trampled", "Led Zeppelin", "Trampled Under Foot", "Remasters (CD2)"),
        track("fade-live", "Metallica", "Fade To Black (Live)", "Live Shit: Binge & Purge"),
        track("breathe", "Evanescence", "Breathe No More", "Anywhere But Home"),
    )
    private val matcher = TrackMatcher(library)

    private fun match(artist: String, title: String, album: String? = null, mbid: String? = null) =
        matcher.match(artist, title, album, mbid)?.id

    @Test
    fun `case, accents and punctuation don't matter`() {
        assertEquals("mauvaise", match("Stromae", "Mauvaise Journee"))
        assertEquals("easily", match("red hot chili peppers", "easily"))
        assertEquals("easily", match("The Red Hot Chili Peppers", "Easily"))
    }

    @Test
    fun `featuring and remaster suffixes are ignored when needed`() {
        assertEquals("bring", match("Evanescence", "Bring Me to Life"))
        assertEquals("battery", match("Metallica", "Battery - Remastered"))
        assertEquals("battery", match("Metallica", "Battery (Remastered 2017)"))
        assertEquals("bring", match("Evanescence feat. Paul McCoy", "Bring Me To Life"))
    }

    @Test
    fun `live versions stay distinct`() {
        assertEquals("whiplash", match("Metallica", "Whiplash"))
        assertEquals("whiplash-live", match("Metallica", "Whiplash (Live)"))
        assertNull(match("Red Hot Chili Peppers", "Easily (Live)"))
    }

    @Test
    fun `the album picks between copies of a song`() {
        assertEquals("immortal-anywhere", match("Evanescence", "My Immortal", "Anywhere But Home"))
        assertEquals("immortal", match("Evanescence", "My Immortal", "Fallen"))
        assertEquals("immortal", match("Evanescence", "My Immortal", "Some Compilation"))
    }

    @Test
    fun `musicbrainz id wins and album artist counts`() {
        assertEquals("smooth", match("MJ", "Something else", mbid = "mbid-smooth"))
        assertEquals("girl", match("Michael Jackson", "The Girl Is Mine"))
        assertEquals("girl", match("Michael Jackson & Paul McCartney", "The Girl Is Mine"))
    }

    @Test
    fun `small spelling differences match within the artist`() {
        assertEquals("olla", match("Luar na Lubre", "Olla meu irmao", "Saudade"))
        assertEquals("place", match("Linkin Park", "Place for My Head", "Hybrid Theory"))
        assertEquals("darkshines", match("Muse", "Dark Shines", "Origin Of Simmetry"))
        assertEquals("exo3", match("Muse", "Exogenesis: Symphony, Pt. 3: Redemption", "The Resistance"))
        assertEquals("trampled", match("Led Zeppelin", "Trampled Underfoot", "Remasters"))
    }

    @Test
    fun `a library subtitle is ignored on the same album`() {
        assertEquals("uah", match("Luar na Lubre", "Uah Lúa", "Hai un paraiso"))
        assertEquals("pretending", match("HIM", "Pretending", "Deep Shadows and Brilliant Highlights"))
        assertNull(match("HIM", "Pretending", "Greatest Lovesongs Vol. 666"))
    }

    @Test
    fun `other recordings still don't match`() {
        assertNull(match("Metallica", "Fade to Black (Live at Rasputin Music)", "Hardwired…To Self-Destruct (Deluxe)"))
        assertNull(match("Evanescence", "Breathe No More (B-Side Version)", "Lost Whispers"))
        assertNull(match("Luar na Lubre", "De Ruada", "Cabo do mundo"))
        assertNull(match("Metallica", "Whiplash (Live at Rasputin Music)"))
    }

    @Test
    fun `songs not in the library don't match`() {
        assertNull(match("Daft Punk", "One More Time"))
        assertNull(match("Metallica", "One"))
    }
}
