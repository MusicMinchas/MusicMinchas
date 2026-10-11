package com.kike.musicplayer.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobbleRuleTest {
    private val min = 60_000L

    @Test
    fun `default is half the song or 4 minutes, whichever comes first`() {
        val rule = ScrobbleRule()
        assertEquals(90_000L, rule.thresholdMs(3 * min))          // 3 min song: 1:30
        assertEquals(4 * min, rule.thresholdMs(10 * min))        // 10 min song: 4:00
        assertEquals(4 * min, rule.thresholdMs(8 * min))         // exactly both
    }

    @Test
    fun `songs of 30 seconds or less are never scrobbled`() {
        assertNull(ScrobbleRule().thresholdMs(30_000))
        assertNull(ScrobbleRule().thresholdMs(12_000))
        assertEquals(15_500L, ScrobbleRule().thresholdMs(31_000))
    }

    @Test
    fun `stricter settings move the threshold later`() {
        assertEquals(162_000L, ScrobbleRule(percent = 90, capMinutes = 10).thresholdMs(3 * min))
        assertEquals(10 * min, ScrobbleRule(percent = 90, capMinutes = 10).thresholdMs(30 * min))
        assertEquals(27 * min, ScrobbleRule(percent = 90, capMinutes = null).thresholdMs(30 * min))
    }

    @Test
    fun `unknown length uses the time limit`() {
        assertEquals(4 * min, ScrobbleRule().thresholdMs(0))
        assertEquals(7 * min, ScrobbleRule(capMinutes = 7).thresholdMs(-1))
        assertEquals(4 * min, ScrobbleRule(capMinutes = null).thresholdMs(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `percent below 50 is refused`() {
        ScrobbleRule(percent = 40)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `time limit below 4 minutes is refused`() {
        ScrobbleRule(capMinutes = 3)
    }
}
