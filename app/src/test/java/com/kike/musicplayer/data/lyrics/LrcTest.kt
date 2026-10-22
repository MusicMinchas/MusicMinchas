package com.kike.musicplayer.data.lyrics

import com.kike.musicplayer.data.lyrics.LyricsRepository.Companion.currentLine
import com.kike.musicplayer.data.lyrics.LyricsRepository.Companion.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcTest {
    @Test
    fun `lrc lines are parsed in time order`() {
        val lines = parseLrc("[00:09.74] I'm gonna make a change\n[00:12.75] For once in my life\n[01:02.5]Third\n[ar:Michael Jackson]\nno tag")
        assertEquals(listOf(9_740L, 12_750L, 62_500L), lines.map { it.timeMs })
        assertEquals("I'm gonna make a change", lines[0].text)
        assertEquals("Third", lines[2].text)
    }

    @Test
    fun `a line with several time tags repeats`() {
        val lines = parseLrc("[00:30.00][01:30.00] Chorus\n[01:00.00] Verse")
        assertEquals(listOf(30_000L to "Chorus", 60_000L to "Verse", 90_000L to "Chorus"), lines.map { it.timeMs to it.text })
    }

    @Test
    fun `empty lines keep their time for pauses`() {
        val lines = parseLrc("[00:10.00] Sing\n[00:20.00] \n[00:30.00] Again")
        assertEquals("", lines[1].text)
    }

    @Test
    fun `the current line follows the position`() {
        val lines = parseLrc("[00:10.00] a\n[00:20.00] b\n[00:30.00] c")
        assertEquals(-1, currentLine(lines, 5_000))
        assertEquals(0, currentLine(lines, 10_000))
        assertEquals(1, currentLine(lines, 29_999))
        assertEquals(2, currentLine(lines, 200_000))
    }

    @Test
    fun `cleanTitle strips remaster and version suffixes`() {
        assertEquals("Stone Cold Crazy", LyricsRepository.cleanTitle("Stone Cold Crazy - Remastered 2011"))
        assertEquals("Stone Cold Crazy", LyricsRepository.cleanTitle("Stone Cold Crazy (Remastered)"))
        assertEquals("Stone Cold Crazy", LyricsRepository.cleanTitle("Stone Cold Crazy [Live at the Rainbow]"))
        assertEquals("Under Pressure", LyricsRepository.cleanTitle("Under Pressure (feat. David Bowie)"))
    }
}
