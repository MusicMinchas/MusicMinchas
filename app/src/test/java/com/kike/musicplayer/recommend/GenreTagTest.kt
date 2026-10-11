package com.kike.musicplayer.recommend

import com.kike.musicplayer.data.metadata.MetadataEnricher.Companion.normalizeGenre
import org.junit.Assert.assertEquals
import org.junit.Test

class GenreTagTest {
    @Test
    fun `genre tags are normalised`() {
        assertEquals("Metal", normalizeGenre("Metal"))
        assertEquals("Rock", normalizeGenre("(17)"))
        assertEquals("Rock", normalizeGenre("17"))
        assertEquals("Heavy Metal", normalizeGenre("(9)Heavy Metal"))
        assertEquals("80s Pop", normalizeGenre("80s Pop"))
        assertEquals("Rock", normalizeGenre(" Rock ; Pop"))
        assertEquals("Drum & Bass", normalizeGenre("Drum  &  Bass"))
        assertEquals(null, normalizeGenre("  "))
        assertEquals("Folk", normalizeGenre("(80)"))
        assertEquals("Gothic Rock", normalizeGenre("(91)"))
        assertEquals("Psychedelic Rock", normalizeGenre("93"))
        assertEquals("Psybient", normalizeGenre("(191)"))
        assertEquals(null, normalizeGenre("(255)"))
    }
}
