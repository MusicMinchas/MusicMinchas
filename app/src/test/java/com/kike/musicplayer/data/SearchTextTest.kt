package com.kike.musicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTextTest {
    @Test
    fun everyWordIsAWordStart() {
        assertEquals("lost* holly*", SearchText.match("Lost Holly"))
        assertEquals("héroes*", SearchText.match("  Héroes "))
    }

    @Test
    fun punctuationSeparatesAndNeverBecomesSyntax() {
        assertEquals("ac* dc*", SearchText.match("AC/DC"))
        assertEquals("don* t* fear*", SearchText.match("don't \"fear\""))
        // "-" can't exclude and "OR" is lower-cased, so both are plain words, not query syntax.
        assertEquals("guns* n* roses* live* or*", SearchText.match("Guns N' Roses -live OR"))
    }

    @Test
    fun titleOnlyVariantForTheSongsTable() {
        assertEquals("title:metallica* title:one*", SearchText.titleMatch("Metallica One"))
        assertNull(SearchText.titleMatch("--"))
    }

    @Test
    fun nothingSearchable() {
        assertNull(SearchText.match(""))
        assertNull(SearchText.match(" -*\"() "))
    }
}
