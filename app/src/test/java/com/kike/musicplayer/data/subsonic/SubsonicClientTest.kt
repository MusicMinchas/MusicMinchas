package com.kike.musicplayer.data.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicClientTest {
    @Test
    fun tokenMatchesTheSpecExample() {
        // http://www.subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d"
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicClient.md5("sesame" + "c19b2d"))
    }

    @Test
    fun addressesAreCleanedUp() {
        val n = SubsonicClient::normalizeAddress
        assertEquals("https://music.example.com", n("music.example.com"))
        assertEquals("https://music.example.com", n("  https://music.example.com/  "))
        assertEquals("https://music.example.com", n("https://music.example.com/app/#/album/123/show"))
        assertEquals("https://music.example.com:4533", n("https://music.example.com:4533/rest"))
        assertEquals("http://192.168.0.5:4533/navidrome", n("http://192.168.0.5:4533/navidrome/app"))
        assertNull(n(""))
        assertNull(n("not an address"))
    }
}
