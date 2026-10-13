package com.kike.musicplayer.data.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NamesTest {
    @Test
    fun mentionsWholeNameWithSameCapitals() {
        assertTrue(Names.mentions("Opeth announce European tour", "Opeth"))
        assertTrue(Names.mentions("Watch Opeth's new video", "Opeth"))
        assertTrue(Names.mentions("Muse share new single", "Muse"))
        assertFalse(Names.mentions("Amazon stops Meta's muse at the door", "Muse"))
        assertFalse(Names.mentions("Museum reopens", "Muse"))
        assertTrue(Names.mentions("Tour news: Muse, Evanescence", "Muse"))
        assertTrue(Names.mentions("System of a Down return", "System of a Down"))
    }

    @Test
    fun googleSourceSuffixIsRemoved() {
        assertEquals("Muse announce shows", Names.stripSource("Muse announce shows - Chaoszine", "Chaoszine"))
        assertEquals("A - B", Names.stripSource("A - B", "NME"))
    }

    @Test
    fun onlyPreciseFutureDatesCountAsUpcoming() {
        val today = LocalDate.of(2026, 10, 13)
        assertEquals(LocalDate.of(2026, 11, 7), Names.upcomingDate("2026-11-07", today))
        assertNull(Names.upcomingDate("2026-10-13", today))
        assertNull(Names.upcomingDate("2026", today))
        assertNull(Names.upcomingDate("2026-10", today))
        assertEquals(LocalDate.of(2026, 11, 1), Names.upcomingDate("2026-11", today))
        assertNull(Names.upcomingDate(null, today))
    }

    @Test
    fun comparableIgnoresAccentsCaseAndPunctuation() {
        assertEquals(Names.comparable("Mauvaise journée"), Names.comparable("MAUVAISE JOURNEE!"))
    }

    @Test
    fun releaseKeyIgnoresEditionNotes() {
        val key = Names.releaseKey("The Greatest 30 Years On Earth")
        assertEquals(key, Names.releaseKey("The Greatest 30 Years On Earth (Live)"))
        assertEquals(key, Names.releaseKey("The Greatest 30 Years On Earth [Deluxe] (Live)"))
        assertEquals(key, Names.releaseKey("The Greatest 30 Years On Earth - Live in Helsinki"))
        assertEquals(Names.comparable("(What's the Story)"), Names.releaseKey("(What's the Story)"))
    }
}
